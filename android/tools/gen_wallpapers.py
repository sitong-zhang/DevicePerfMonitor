#!/usr/bin/env python3
"""生成 1.0.26 新增内置壁纸（极光紫 / 薄荷青绿）+ 设置页缩略图。

风格对齐现有 wallpaper_light.jpg（1024x2220）：浅色竖向渐变底 +
数块超出画布、经强高斯模糊的半透明大椭圆（边缘必须软到看不出边界）+
极淡的横向明暗带（模拟原图的细腻纹理）。保持浅色亮度，使 AppNav 里
既有的深色罩层（0.30/0.16/0.34）继续适配深色主题文字。
"""
import os
from PIL import Image, ImageDraw, ImageFilter

W, H = 1024, 2220
OUT = "/workspace/DevicePerfMonitor/android/app/src/main/res/drawable-nodpi"


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vertical_gradient(top, bottom):
    base = Image.new("RGB", (1, H))
    px = base.load()
    for y in range(H):
        px[0, y] = lerp(top, bottom, y / (H - 1))
    return base.resize((W, H), Image.BILINEAR)


def soft_blob(cx, cy, sw, sh, rgba, blur, canvas):
    """画一块远超画布、强模糊的椭圆，边缘彻底柔掉。"""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse(
        [cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2],
        fill=rgba,
    )
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def subtle_bands(canvas, alpha=9, period=9, stripe=2):
    """极淡横向明暗带：给纯色块一点细腻纹理（模仿原壁纸的扫线感）。"""
    w, h = canvas.size
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    y = 0
    while y < h:
        d.rectangle([0, y, w, y + stripe], fill=(0, 0, 0, alpha))
        y += period
    canvas.alpha_composite(layer)


def compose(top, bottom, blobs, name, bands_alpha=7):
    img = vertical_gradient(top, bottom).convert("RGBA")
    for cx, cy, sw, sh, rgba, blur in blobs:
        soft_blob(cx, cy, sw, sh, rgba, blur, img)
    subtle_bands(img, alpha=bands_alpha)
    # 轻微提亮，保证浅色基调
    img = Image.eval(img.convert("RGB"), lambda v: min(255, int(v * 1.02 + 3)))
    path = os.path.join(OUT, name)
    img.save(path, "JPEG", quality=88, optimize=True)
    print(name, img.size, os.path.getsize(path) // 1024, "KB")


def thumb(src, name, tw=320, th=694):
    im = Image.open(os.path.join(OUT, src))
    sw, sh = im.size
    scale = max(tw / sw, th / sh)
    im2 = im.resize((int(sw * scale), int(sh * scale)), Image.BILINEAR)
    x = (im2.width - tw) // 2
    y = (im2.height - th) // 2
    im2.crop((x, y, x + tw, y + th)).save(
        os.path.join(OUT, name), "JPEG", quality=82, optimize=True
    )
    print(name, (tw, th), os.path.getsize(os.path.join(OUT, name)) // 1024, "KB")


# ---- 极光紫：浅薰衣草底 + 紫/粉/蓝大色块 ----
compose(
    top=(234, 228, 250),
    bottom=(248, 242, 253),
    blobs=[
        # 右上：主体紫
        (1.05 * W, -0.10 * H, 1.7 * W, 1.1 * H, (143, 110, 218, 165), 170),
        # 左中：粉紫
        (-0.15 * W, 0.40 * H, 1.6 * W, 1.0 * H, (198, 150, 228, 130), 180),
        # 右下：雾蓝
        (0.75 * W, 1.05 * H, 1.5 * W, 0.9 * H, (104, 134, 222, 140), 170),
        # 中右小亮点：暖粉
        (0.90 * W, 0.60 * H, 0.8 * W, 0.5 * H, (255, 196, 224, 80), 130),
    ],
    name="wallpaper_aurora.jpg",
)

# ---- 薄荷青绿：浅薄荷底 + 青/绿/蓝大色块 ----
compose(
    top=(224, 245, 239),
    bottom=(241, 251, 246),
    blobs=[
        # 左上：主体青绿
        (-0.05 * W, 0.05 * H, 1.6 * W, 1.0 * H, (86, 192, 162, 150), 170),
        # 右侧：湖蓝
        (1.00 * W, 0.45 * H, 1.4 * W, 0.9 * H, (64, 162, 202, 140), 170),
        # 底部：薄荷
        (0.30 * W, 1.05 * H, 1.6 * W, 0.8 * H, (124, 212, 182, 130), 160),
        # 顶部白雾高光
        (0.55 * W, -0.08 * H, 0.9 * W, 0.4 * H, (255, 255, 255, 110), 120),
    ],
    name="wallpaper_mint.jpg",
)

# ---- 缩略图（设置页壁纸选择行用，避免整图解码占内存）----
thumb("wallpaper_light.jpg", "wallpaper_thumb_light.jpg")
thumb("wallpaper_aurora.jpg", "wallpaper_thumb_aurora.jpg")
thumb("wallpaper_mint.jpg", "wallpaper_thumb_mint.jpg")
