// secvault.c —— 安全加固 Native 层（对应需求：SO 层虚拟化简洁实现 + 反逆向辅助）
//
// 包含：
//   1) 自定义虚拟机(VM)解释器：token 后半 B 以「VM 字节码」形式存放（见 generated_shard.h
//      的 g_b_vm），运行时由本解释器还原——静态看不到 B 明文（代码虚拟化）。
//   2) XOR key 提供：token 前半 A 的 XOR key 由 SO 提供（DEX 侧只存密文）。
//   3) 环境检测（反逆向辅助，软失败）：Root / Frida / 调试器 / 模拟器命中时返回
//      dirty=1，TokenVault 据此不解密、返回 null（不阻断 App 其它功能）。
//
// 注：客户端本地加固只能提高逆向成本。本文件是「提高成本」的工程实现；真正的安全
// 边界仍是 token 的最小权限（详见交付说明）。

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <dlfcn.h>

#include "generated_shard.h"

// ---- 简易动态 buffer（用于 VM 输出 B 明文） ----
typedef struct {
    unsigned char *buf;
    int len;
    int cap;
} DynBuf;

static void db_init(DynBuf *d) {
    d->cap = 64;
    d->len = 0;
    d->buf = (unsigned char *)malloc(d->cap);
}

static void db_push(DynBuf *d, unsigned char b) {
    if (d->len >= d->cap) {
        d->cap *= 2;
        d->buf = (unsigned char *)realloc(d->buf, d->cap);
    }
    d->buf[d->len++] = b;
}

// ---- VM 解释器：还原 B ----
// 指令 3 字节 [op, arg0, arg1]：
//   0x01 LOAD_IMM  acc = arg0
//   0x02 VM_XOR    acc = acc ^ arg0
//   0x03 VM_ADD    acc = (acc + arg0) & 0xFF
//   0x04 EMIT      db_push(acc)
//   0xFF HALT
static void run_vm(const unsigned char *code, int code_len, DynBuf *out) {
    int pc = 0;
    int acc = 0;
    while (pc + 2 < code_len) {
        int op = code[pc];
        int a0 = code[pc + 1];
        // a1 = code[pc + 2]（部分指令不用）
        switch (op) {
            case 0x01: acc = a0; break;
            case 0x02: acc = (acc ^ a0) & 0xFF; break;
            case 0x03: acc = (acc + a0) & 0xFF; break;
            case 0x04: db_push(out, (unsigned char)(acc & 0xFF)); break;
            case 0xFF: return;
            default: return; // 未知/越界，安全停机
        }
        pc += 3;
    }
}

// MBA（混合布尔算术）恒真谓词：编译期难以被去混淆，运行时恒为 0（不纯判定不依赖它）
static int mbap_probe(void) {
    volatile int x = 0x5A;
    int r = ((x * 3 + x) / 4 - x) + ((x ^ x) | (x & 0));
    // 恒为 0；插入以扰乱控制流/反编译阅读
    return r;
}

// ---- 环境检测 ----
static int file_exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

// 调试器：/proc/self/status 的 TracerPid 非 0
static int is_traced(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    int traced = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            if (pid != 0) traced = 1;
            break;
        }
    }
    fclose(f);
    return traced;
}

// 调试器：ptrace 自附着（已被调试则返回 -1/EPERM）
static int ptrace_self(void) {
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        return errno == EPERM ? 1 : 0;
    }
    ptrace(PTRACE_DETACH, 0, 0, 0);
    return 0;
}

// Frida：检测常见痕迹（端口 27042 / maps 中的 frida / 注入库）
static int has_frida(void) {
    // 端口 27042 (0x69A2) 监听
    FILE *f = fopen("/proc/net/tcp", "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, ":69A2")) { fclose(f); return 1; }
        }
        fclose(f);
    }
    // maps 含 frida 字样
    f = fopen("/proc/self/maps", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "frida") || strstr(line, "gadget")) { fclose(f); return 1; }
        }
        fclose(f);
    }
    // 注入库
    if (dlopen("libfrida-agent.so", RTLD_NOLOAD) ||
        dlopen("libfrida-gadget.so", RTLD_NOLOAD)) {
        return 1;
    }
    return 0;
}

// Root：常见 su 二进制 / magisk 路径
static int is_rooted(void) {
    const char *paths[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/system/su", "/system/bin/.ext/.su",
        "/magisk/.core/bin/su", "/data/adb/magisk/bin/su",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        if (file_exists(paths[i])) return 1;
    }
    return 0;
}

// 模拟器：qemu 管道 / 特征文件
static int is_emulator(void) {
    if (file_exists("/dev/qemu_pipe") || file_exists("/dev/socket/qemud")) return 1;
    FILE *f = fopen("/proc/self/cmdline", "r");
    if (f) {
        char line[256];
        size_t n = fread(line, 1, sizeof(line) - 1, f);
        line[n] = 0;
        fclose(f);
        if (strstr(line, "qemu") || strstr(line, "goldfish") || strstr(line, "generic")) return 1;
    }
    return 0;
}

// ===================== JNI 导出 =====================

JNIEXPORT jbyteArray JNICALL
Java_com_molecule_security_TokenVault_nativeGetXorKey(JNIEnv *env, jobject thiz) {
    (void)thiz;
    jbyteArray arr = (*env)->NewByteArray(env, g_xor_key_len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, g_xor_key_len, (jbyte *)g_xor_key);
    }
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_molecule_security_TokenVault_nativeGetShardB(JNIEnv *env, jobject thiz) {
    (void)thiz;
    DynBuf out;
    db_init(&out);
    run_vm(g_b_vm, g_b_vm_len, &out);
    jbyteArray arr = (*env)->NewByteArray(env, out.len);
    if (arr && out.len > 0) {
        (*env)->SetByteArrayRegion(env, arr, 0, out.len, (jbyte *)out.buf);
    }
    free(out.buf);
    return arr;
}

// 返回 1 表示环境不纯（Root/Frida/调试器/模拟器），TokenVault 据此软失败不解密
JNIEXPORT jboolean JNICALL
Java_com_molecule_security_TokenVault_nativeIsHostDirty(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    (void)mbap_probe(); // 恒真谓词，扰乱
    int dirty = 0;
    if (is_traced()) dirty = 1;
    if (ptrace_self()) dirty = 1;
    if (has_frida()) dirty = 1;
    if (is_rooted()) dirty = 1;
    if (is_emulator()) dirty = 1;
    return (jboolean)(dirty ? 1 : 0);
}
