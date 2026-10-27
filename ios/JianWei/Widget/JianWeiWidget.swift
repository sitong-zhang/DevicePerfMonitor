import WidgetKit
import SwiftUI

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> SimpleEntry {
        SimpleEntry(date: Date(), cpu: 0, score: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping (SimpleEntry) -> Void) {
        completion(SimpleEntry(date: Date(), cpu: 0, score: 0))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SimpleEntry>) -> Void) {
        // 实际数据通过 App Group 从主 App 写入；此处给占位，详见 README。
        let entry = SimpleEntry(date: Date(), cpu: 0, score: 0)
        let timeline = Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(60)))
        completion(timeline)
    }
}

struct SimpleEntry: TimelineEntry {
    let date: Date
    let cpu: Double
    let score: Int
}

struct JianWeiWidgetEntryView: View {
    var entry: Provider.Entry
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("见微").font(.headline)
            Text("CPU \(Int(entry.cpu))%").font(.subheadline)
            Text("分 \(entry.score)").font(.subheadline)
        }
        .padding()
    }
}

struct JianWeiWidget: Widget {
    let kind = "JianWeiWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            JianWeiWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("见微 监测")
        .description("实时设备性能概览")
        .supportedFamilies([.systemSmall])
    }
}
