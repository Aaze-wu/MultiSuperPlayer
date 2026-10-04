# 发版流程

三条线，两个通道。这份文件只管**操作顺序**；为什么这么分档（以及为什么更新页里只有两个
选项）写在 `README.md` 的「应用更新」一节里。

## 一、三条线

| 版本号 | 是什么 | 对外 | GitHub Release | 更新页里 |
| --- | --- | --- | --- | --- |
| `X.Y.Z-alpha.N` | 内部构建 | ❌ | **不建** | 两条通道都不收 |
| `X.Y.Z-beta.N` | 公开测试版 | ✅ | 建，`--prerelease` | 只有「测试版」通道收 |
| `X.Y.Z` | 正式版 | ✅ | 建，Latest | 两条通道都收 |

一句话：**`alpha` 是「我自己先用」，`beta` 是「你们先用」，不带后缀是「都用」。**

## 二、每条线都要做的事

### 1. 改版本号（只有一个地方）

`app/build.gradle.kts` 顶部的 `val appVersionName`。`versionCode`、`BuildConfig`、
关于页显示的版本号全部由它推导，**不要再在任何地方写第二遍数字**。

```kotlin
val appVersionName: String = "0.9.0-beta.1"
```

写错了会在构建时直接失败，不会带病出门：

- 数值部分必须是 `x.y.z` 三段，且 `minor` / `patch` 都小于 100（否则会和
  `major*10000 + minor*100 + patch` 的编码撞车）；
- 预发行后缀必须以字母通道名开头（`[A-Za-z]+`），所以 `-beta.1` 可以，`-1` 不行。

### 2. 写发布说明

`docs/release-notes/<版本号>.md`，格式照 `v0.8.1.md`。

这份文件是 **Release 正文的来源**，不是「写给人看的内部文档」：更新页会把整段内容显示给
用户，所以它就是「这一版做了什么」的唯一一份说明，不用再维护第二份。

### 3. 先提交、再打 tag（顺序不能反）

版本号和发布说明改完之后**先提交**；beta / 正式版还要**先打 tag**，然后才编译。

理由不是洁癖：`assembleRelease` 在**配置阶段**就执行 `git describe --tags --always --dirty`，
把结果注进 `BuildConfig.GIT_TAG` / `GIT_DIRTY`（见 `app/build.gradle.kts` 里的 `gitDescribe`）。
先编译后提交，包里带的就是**上一版的 tag** 加一个 `-dirty`，于是日志抬头（`MspLogInitializer`
的「会话开始」块、以及导出报告的抬头）会写：

```
来源: v0.9.0（1bbbac4，含未提交改动）      ← 这个包其实是 1.0.0
```

而那段抬头存在的理由正是「这段日志是哪次运行的、装的哪个包」——它一旦说错，排查的人会把
新版本的 bug 当成旧版本的。

```powershell
git add -A
git commit -m "feat(vX.Y.Z): <一行摘要>"
git tag -a vX.Y.Z -m "vX.Y.Z"        # alpha 不要这一步，见下
```

**alpha 不打 tag**：它的抬头会写成 `v0.9.0-3-gabc1234`，读作「在 v0.9.0 之后第 3 个提交」。
这不是错，内部包本来就该长这样。

**要重建一个已发布版本的包时，必须在那个 tag 上编译**（HEAD 停在 tag 提交、工作区干净）。
在 tag 之后又提交了东西的 HEAD 上编译，抬头会变成 `v1.0.0-1-g8a1ac17` —— 那个包不再对应
Release 里挂着的那个，两者 sha256 对不上时也就说不清是哪一份的问题了。

### 4. 编译并归档

```powershell
.\gradlew.bat assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`，归档一份到
`dist/MultiSuperPlayer-v<版本号>.apk`。`dist/` 只是本地留档，不是发布渠道。

**alpha 编到这里就够**：装到自己机器上试，不建 Release。

### 5. 建 Release（alpha 跳过这一步）

```powershell
# 公开测试版
gh release create v0.9.0-beta.1 dist/MultiSuperPlayer-v0.9.0-beta.1.apk `
    --prerelease --title "v0.9.0-beta.1 标题" --notes-file docs/release-notes/v0.9.0-beta.1.md

# 正式版
gh release create v0.9.0 dist/MultiSuperPlayer-v0.9.0.apk `
    --latest --title "v0.9.0 标题" --notes-file docs/release-notes/v0.9.0.md
```

**`--prerelease` 别漏。** 判断「这一版是不是测试版」看的是 tag（见 README），但漏勾会让
GitHub 页面把它显示成最新正式版——用户在应用里看到「已是最新」，旁边却挂着一个「Latest」
的测试版。加不加 `--prerelease` 也决定 GitHub 会不会把它标成 Latest：**只有非预发行版本
会被自动标成 Latest**，所以 beta 那一行不用再写 `--latest=false`。

## 三、beta 测完，怎么转正式版

beta 期间会有修 bug 的提交，转正式版就三步：

1. `appVersionName` 改成 `0.9.0` ——**去掉后缀就是「升上去」，不是换一个新版本号**；
2. beta 期间的修复内容**追加**进 `docs/release-notes/v0.9.0.md`。**不要去改 `-beta.1` 那份**：
   那份已经是发给用户的公告了，改了之后同一个 tag 下面的说明和用户当初读到的不一样；
3. 提交、打新 tag `v0.9.0`，**然后才是** `assembleRelease` → 建 Release，**不加 `--prerelease`**，
   加 `--latest`。顺序照第二节第 3 步，那是现在唯一一件光看代码看不出来、却会让日志抬头
   说错版本的事。

（beta 的 tag `v0.9.0-beta.1` 留着不动：它指向的是 beta 那次提交，重打会让已经装了 beta 的
人抬头里的 tag 和包里的代码对不上。）

**不需要**动 `versionCode`：同一数值段的 alpha / beta / 正式版共用一个 code，这是有意的
（Android 只在**降级**时拒绝安装，同 code 覆盖安装是允许的）。
但**进到下一个数值段时 code 必须跟着涨**（`0.9.0` → `0.10.0` 是 1000，不是 900），
否则预发行版会变成用户永远装不上的降级包。

## 四、发完要看四件事

1. **`git describe --tags` 和包里的 `versionName` 是同一个号**。包里注入的 tag 出现在**日志
   抬头**（关于页只显示版本号，不显示来源）：导出一次日志，确认抬头那行 `来源: …` 是这一版
   的 tag、而且**不带**「含未提交改动」。两个号不一致是「版本号改了一处、忘了另一处」；
   带上「含未提交改动」则是**编译排在了提交之前**（见第二节第 3 步）——这也是当初把版本号
   收成唯一来源的原因。
2. **关于页的徽章**：beta 显示「测试版」，alpha 显示「预览版」，正式版不显示徽章。
3. **更新页两条通道各试一次**：切到「正式版」，确认看不到刚发的 beta；切到「测试版」，
   确认看得到。这一步是在验 `UpdateChannel.allows`，不是在验界面。
4. **大小和 sha256** 和 Release 里写的一致（`Get-FileHash`）。

## 五、几件容易忘的事

- **更新检查读的是 Releases，不是 tag。** 所以「打了 tag 但没建 Release」等于没发布，
  用户在更新页里一个东西也看不到。反过来，不打算发布的版本只要不建 Release，
  更新通道里就永远不会出现它——`UpdateChannel` 里那条排除 alpha 的规则是**第二道**闸，
  防的是「哪天顺手建了一个」。
- **Release 只能挂一个 APK。** 更新逻辑取的是「一个或零个 APK 资产」，多传几个包不会报错，
  只会让更新页拿着第一个去装。
- **重建 Release 不要动 tag。** 先 `gh release delete <tag>`，再 `gh release create <tag> ...`
  （tag 本来就存在，`gh` 会直接用它建 Release）。**不要 `git tag -d` 重打**：已经装了那个包的
  人，抬头里那行 `来源: …` 会和包里的代码对不上，而那正是「版本号必须唯一来源」想避免的事。
  （重建时自己要在 tag 那个提交上编译，见第二节第 3 步。）
- **`dist/` 里不要放同名不同内容的包。** 那里是留档；同一个文件名下出现两份不同的内容，
  以后 sha256 对不上时没人查得出是哪一份的问题。
