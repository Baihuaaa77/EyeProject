# 光码映光独立实验 v0.5.0

独立iPhone验证应用。包含原生像素眼区、手动曝光、快速明暗码、全部错码对照、空间模板和实验清单导入。研究原型，需真机确认光学来源。

## 构建

GitHub Actions入口：`.github/workflows/corneal-code-lab.yml`。先运行便携算法/Python回归，再用XcodeGen与CocoaPods构建模拟器测试和未签名真机IPA。

本目录执行：`swift test -c release`、`python3 -B -m unittest discover -s tools -p "test_*.py"`。

KMP、MediaPipe配置及模型沿用本次分支的共用核心；实验使用的私人记录和视频不纳入仓库。
