import WidgetKit
import SwiftUI

/// 小组件目标入口。需作为独立「Widget Extension」目标加入工程（见 ios/README.md）。
@main
struct JianWeiWidgetBundle: WidgetBundle {
    var body: some Widget {
        JianWeiWidget()
    }
}
