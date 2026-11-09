# 数字字体

首页指数采用 Nunito Black（900 字重），只作用于数字，不改变中文排版。

- 来源：[Google Fonts 官方字体目录](https://github.com/google/fonts/tree/main/ofl/nunito)。
- 原始文件：`Nunito[wght].ttf`，使用 fontTools 在 900 字重生成静态实例，PostScript 名称 `Nunito-Black`。
- 字体资源：`src/main/res/font/nunito_black.ttf`，Android 和 iOS 共用同一文件。
- SHA-256：`f19ba04917aedea235eeebe6dc91f1bccc77ab78439a6d8fcbd9a58ecd9c076b`。
- 授权：SIL Open Font License 1.1；版权及完整许可位于 `src/main/assets/fonts/OFL-Nunito.txt`，随两端应用打包。
- iOS 通过 project.yml 的 resources 与 UIAppFonts 注册；工程检查会检查字体和授权是否漏包。
