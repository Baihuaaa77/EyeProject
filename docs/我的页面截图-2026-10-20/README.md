# “我的”页面与用户资料管理截图

2026-10-20，通过当前 Android Compose 页面组件渲染，390 × 844 像素。成员、健康资料、账号与记录均为演示数据；云端截图展示已登录状态，不代表当前已连接线上服务。

| 截图 | 展示内容 |
|---|---|
| 01-我的-家庭成员总览.png | 本人、孩子、父母的独立资料入口、填写进度、添加家庭成员 |
| 02-成员资料-基本信息.png | 成员称呼、出生年份、性别、保存入口 |
| 03-成员资料-视力与验光.png | 检查日期、小数视力、球镜/柱镜和轴位，保留正负号 |
| 04-成员资料-近期用药.png | 药名、频次、起止日期、隐形眼镜类型 |
| 05-成员资料-隐形眼镜.png | 隐形眼镜类型与更换周期 |
| 06-成员资料-家族与过敏史.png | 家族眼病多选及过敏记录 |
| 07-我的-云端同步-演示状态.png | 分项同意、同步统计、导出、退出和注销入口 |
| 08-本机记录-报告与导出.png | 历史结论、可靠性与报告明细、TXT/JSON 两种导出 |

截图脚本：`feature/mine/src/test/java/com/zkyjss/eyeproject/feature/mine/MineWalkthroughCapture.kt`。

重新生成：

```powershell
.\gradlew.bat :feature:mine:testDebugUnitTest --tests '*MineWalkthroughCapture'
```
