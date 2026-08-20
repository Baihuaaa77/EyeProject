# ResNet 技术文档 —— 残差网络详解

> 本文面向「已懂卷积、但没吃透 ResNet」的读者：从「卷积核就是被训练的数字」这个前提出发，
> 讲清残差是什么、ResNet 的结构长什么样、代码逐行对应什么，以及它在 L2CS-Net（EyeProject）里扮演的角色。

## 1. 一句话概述

**ResNet = Residual Network（残差网络）**，微软何恺明团队 2015 年在论文
[*Deep Residual Learning for Image Recognition*](https://arxiv.org/abs/1512.03385) 中提出。

它对「堆卷积层」只做了一处改动——**在每个块的输出后面把输入原样加回来（`+x`）**——就解决了
「网络越深越退化」的问题，让网络能稳定堆到 50 / 101 / 152 层。ResNet 是图像领域最常用的
**backbone（主干/特征提取器）** 之一，EyeProject 的 L2CS-Net 用 **ResNet-50** 当主干。

## 2. 它要解决什么问题

直觉上「网络越深，效果越好」。但实验发现不是：当网络深到一定程度，**再加深，训练误差反而升高**。

这不是过拟合——过拟合是「训练好、测试差」（背答案没真懂），而这里是**连训练集都变差**，叫
**退化问题（degradation）**。

原因大致有两点：

1. **梯度消失**：反向传播时梯度一路连乘，很多因子 <1，传到最前面几层就趋近 0，前面的层几乎不更新。
2. **恒等映射难学**：深网络很难学会「原样复制输入」这种简单的事。

## 3. 核心思想：残差连接（skip connection）

ResNet 的解法非常朴素：**让网络别去学整个输出，只学「输出与输入的差」**。

普通卷积块：

```
输入 x ──► [卷积 + BN + ReLU] ──► [卷积 + BN] ──► 输出 F(x)
```

残差块（多了右边那条「抄近路」的线）：

```
输入 x ──► [卷积+BN+ReLU] ──► [卷积+BN] ──► F(x) ─┐
   │                                               (+)──► 输出 = F(x) + x
   └────────────── 恒等捷径 (identity shortcut) ─────┘
```

关键就是最后那步 **`输出 = F(x) + x`**：把输入原样加回去。那条 `+x` 的线没有参数、不参与训练，
就是一根「电线」。

## 4. 「残差」到底是什么意思

**残差 = 差值 =「还差多少」。**

- 普通网络：权重 `w` 学「把输入 x 变成目标 y」，即学整个映射 `y`。
- 残差网络：权重 `w` 学「输入离目标还差多少」，即学差值 `y − x`。

举例（输入 x=5，目标 y=5.1，一层就是 `w×x`）：

| 网络类型 | 计算式 | 解出 w |
|---|---|---|
| 普通 | w×5 = 5.1 | w = **1.02**（要精确复制再放大） |
| 残差 | w×5 + 5 = 5.1 | w = **0.02**（只补 0.1 的差） |

所以：

> **残差网络训练的权重，还是卷积核里那些数字，和普通 CNN 一模一样。**
> 唯一变的，是这些数字「被训练成什么」：从「学整个输出」变成「学差值」。

## 5. 为什么有效

1. **恒等映射变容易**：如果某一层最理想就是「啥也不改」，普通网络需要把权重精确学成 1；
   残差网络只要把 `F(x)` 学成 **0**，输出还是 `x`。学 0 比学精确的 1 容易得多。
   于是每层最坏情况是「学成 0、原地不动」，怎么堆都不会更糟。

2. **梯度能直通**：反向传播时，梯度除了走卷积那条路，还能顺着 `+x` 捷径**原样传回**，
   相当于给梯度修了条高速路，深层网络也不容易梯度消失。

## 6. 两种「块」（block）

| 型号 | 用的块 | 块内部结构 | expansion（通道放大倍数） |
|---|---|---|---|
| ResNet-18 / 34 | **BasicBlock** | 两个 3×3 卷积 | 1 |
| ResNet-50 / 101 / 152 | **Bottleneck** | 1×1 → 3×3 → 1×1 | 4 |

**Bottleneck（瓶颈块）** 是 ResNet-50 起的做法，为了省计算：

```
输入 C 通道 ─► 1×1 卷积压到 C/4 ─► 3×3 卷积 ─► 1×1 卷积扩回 C ─► +x ─► 输出
              ↑ 先"降维"省算力    ↑ 真正提特征   ↑ 再"升维"还原
```

先用 1×1 把通道压下去、再扩回来，比直接两个 3×3 便宜得多，所以 50 层也能训练。

## 7. ResNet 型号对照表

型号里的数字由「`conv1 + 4 组残差块 + 全连接层`」的层数决定，代码里的 `layers` 数组
（如 `[3,4,6,3]`）就是 4 组各自堆几个块：

| 型号 | 块类型 | layers（4 组块数） | 约参数量 |
|---|---|---|---|
| ResNet-18 | BasicBlock | [2,2,2,2] | 1100 万 |
| ResNet-34 | BasicBlock | [3,4,6,3] | 2100 万 |
| **ResNet-50** | **Bottleneck** | **[3,4,6,3]** | **2500 万** |
| ResNet-101 | Bottleneck | [3,4,23,3] | 4400 万 |
| ResNet-152 | Bottleneck | [3,8,36,3] | 6000 万 |

**「50」怎么数**（Bottleneck 每个块含 3 个卷积层）：

```
conv1                               1 层
layer1: 3 块 × 3 卷积 =  9 层
layer2: 4 块 × 3 卷积 = 12 层
layer3: 6 块 × 3 卷积 = 18 层
layer4: 3 块 × 3 卷积 =  9 层
最后的全连接 fc                     1 层
合计 = 1 + 9 + 12 + 18 + 9 + 1 = 50 层 ✅
```

## 8. ResNet-50 的完整结构

```
输入图片  224×224×3（高×宽×通道）
    │
    ▼
[conv1]   7×7 卷积, stride=2 ───────►  112×112×64
    │
    ▼
[maxpool] 3×3 池化, stride=2 ───────►  56×56×64
    │
    ▼
[layer1]  3 个 Bottleneck ──────────►  56×56×256
    │
    ▼
[layer2]  4 个 Bottleneck ──────────►  28×28×512
    │
    ▼
[layer3]  6 个 Bottleneck ──────────►  14×14×1024
    │
    ▼
[layer4]  3 个 Bottleneck ──────────►  7×7×2048
    │
    ▼
[全局平均池化] ─────────────────────►  2048 个数
    │
    ▼
[全连接层] ─────────────────────────►  输出（分类分数）
```

**整体规律（最重要的一句话）**：

> 图越走越「细长」：**空间尺寸一路 ÷2 变小，通道数一路 ×2 变大**，最后压成一条向量。

- 高宽：224 → 112 → 56 → 28 → 14 → 7，每过一个阶段小一半；
- 通道：3 → 64 → 256 → 512 → 1024 → 2048，越往后越多；
- 空间总共缩小 32 倍（conv1÷2、maxpool÷2、layer2/3/4 各÷2）。

> 注：EyeProject 的 L2CS-Net 实际输入是 **448×448**（见 `utils.py` 里的 `Resize(448)`），
> 所以上面所有高宽数字 ×2（112→224、56→112……），通道不变，规律一致。

### 8.1 四个 stage 的规格

代码里 `_make_layer(block, planes, blocks, stride)` 中的 `planes` 是**中间通道**，输出通道 =
`planes × 4`（Bottleneck 的 expansion=4）：

| stage | 块数 | 中间通道 | 输出通道 | 空间尺寸变化 |
|---|---|---|---|---|
| layer1 | 3 | 64 | 256 | 不变 |
| layer2 | 4 | 128 | 512 | ÷2（首块 stride=2） |
| layer3 | 6 | 256 | 1024 | ÷2（首块 stride=2） |
| layer4 | 3 | 512 | 2048 | ÷2（首块 stride=2） |

**每个 stage 只有第一个块做下采样**（stride=2），组内其余块只加深、尺寸不变。

## 9. 卷积核与通道的关系（澄清常见误区）

对任意卷积层 `nn.Conv2d(C_in, C_out, k)`：

- **核的形状** = `k × k × C_in`（一个「立体盒子」，深 = 输入通道数）；
- **核的个数** = `C_out`；
- **每个核产 1 个通道**（把 `k×k×C_in` 个数相乘再求和 → 1 个数），所以输出 = `C_out` 个通道；
- **参数个数** = `k × k × C_in × C_out`（bias=True 时再加 C_out）。

套到 conv1 `nn.Conv2d(3, 64, 7)`：

- 每个核 = 7×7×**3**（深 3，吃掉 RGB 三通道）；
- 共 **64** 个核；
- 参数 = 7×7×3×64 = **9408** 个数字；
- 输出 = **64** 个通道（不是 64×3）。

> 记忆口诀：**1 个核 → 1 个通道；核的深度 = 输入通道数；核的个数 = 输出通道数。**

## 10. 结合 L2CS-Net 代码逐行讲解

以 `EyeProject` 依赖的 `l2cs/model.py` 为例（`block = torchvision 的 Bottleneck`，`num_bins = 90`）。

### 10.1 骨架：`__init__`

```python
class L2CS(nn.Module):
    def __init__(self, block, layers, num_bins):
        self.inplanes = 64            # 计数器：当前通道数
        self.conv1  = nn.Conv2d(3, 64, kernel_size=7, stride=2, padding=3, bias=False)
        self.bn1    = nn.BatchNorm2d(64)
        self.relu   = nn.ReLU(inplace=True)
        self.maxpool= nn.MaxPool2d(kernel_size=3, stride=2, padding=1)
        self.layer1 = self._make_layer(block, 64,  layers[0])          # 3 块
        self.layer2 = self._make_layer(block, 128, layers[1], stride=2) # 4 块
        self.layer3 = self._make_layer(block, 256, layers[2], stride=2) # 6 块
        self.layer4 = self._make_layer(block, 512, layers[3], stride=2) # 3 块
        self.avgpool = nn.AdaptiveAvgPool2d((1, 1))
        self.fc_yaw_gaze   = nn.Linear(512 * block.expansion, num_bins)  # 2048→90
        self.fc_pitch_gaze = nn.Linear(512 * block.expansion, num_bins)  # 2048→90
        # Vestigial layer from previous experiments（残留死代码，forward 未使用）
        self.fc_finetune = nn.Linear(512 * block.expansion + 3, 3)
```

- `conv1(3, 64, 7, stride=2, padding=3)`：3→64 通道、图缩小一半；padding=3 让 224 输入恰好变 112。
- `layer1~4`：4 组残差块，`layers=[3,4,6,3]`，layer2/3/4 首块 `stride=2` 缩小一半。
- `avgpool`：把任意尺寸压成 1×1，2048 通道 → 2048 个数。
- `512 * block.expansion` = `512×4 = 2048`，所以两个分类头都是 `Linear(2048, 90)`。
- `fc_finetune` 被定义但 `forward` 里不用——作者旧实验残留，不影响结果。

### 10.2 初始化权重

```python
for m in self.modules():
    if isinstance(m, nn.Conv2d):
        n = m.kernel_size[0] * m.kernel_size[1] * m.out_channels
        m.weight.data.normal_(0, math.sqrt(2. / n))   # Kaiming 初始化
    elif isinstance(m, nn.BatchNorm2d):
        m.weight.data.fill_(1)                          # γ 填 1
        m.bias.data.zero_()                             # β 填 0
```

给所有卷积核设初始值（正态随机，标准差 `sqrt(2/n)`，即 Kaiming 初始化），BN 先设为「不缩放不平移」。
训练开始后，这些数字会被梯度下降不断调整。

### 10.3 造一个 stage：`_make_layer`

```python
def _make_layer(self, block, planes, blocks, stride=1):
    downsample = None
    if stride != 1 or self.inplanes != planes * block.expansion:
        downsample = nn.Sequential(
            nn.Conv2d(self.inplanes, planes * block.expansion,
                      kernel_size=1, stride=stride, bias=False),
            nn.BatchNorm2d(planes * block.expansion),
        )
    layers = []
    layers.append(block(self.inplanes, planes, stride, downsample))  # 第一个块
    self.inplanes = planes * block.expansion                          # 更新通道计数
    for i in range(1, blocks):
        layers.append(block(self.inplanes, planes))                   # 其余块（stride=1，无 downsample）
    return nn.Sequential(*layers)
```

- **什么时候需要 `downsample`？** 当 `stride≠1`（要缩小尺寸）或通道数变化（`inplanes ≠ 输出通道`）时，
  捷径线不能「原样把 x 加回来」，需要配一个 **1×1 卷积**把 x 对齐到和输出同尺寸同通道。
- **`+x` 在哪？** 这个文件里看不到——它写在 torchvision 的 `Bottleneck` 内部；这里的 `downsample`
  只是给「维度不匹配」时提供一条有参数的对齐路径。维度匹配时捷径直接 `+x`，无参数。
- 第一个块带 stride/downsample（可能缩小/变通道），其余块 stride=1、无 downsample（尺寸通道都不变）。

### 10.4 前向传播：`forward`

```python
def forward(self, x):
    x = self.conv1(x)      # 224→112，3→64
    x = self.bn1(x)
    x = self.relu(x)
    x = self.maxpool(x)    # 112→56
    x = self.layer1(x)     # 56×56×64  → 56×56×256
    x = self.layer2(x)     # → 28×28×512
    x = self.layer3(x)     # → 14×14×1024
    x = self.layer4(x)     # → 7×7×2048
    x = self.avgpool(x)    # → 1×1×2048
    x = x.view(x.size(0), -1)              # 摊平成 [batch, 2048]
    pre_yaw_gaze   = self.fc_yaw_gaze(x)   # 2048 → 90
    pre_pitch_gaze = self.fc_pitch_gaze(x) # 2048 → 90
    return pre_yaw_gaze, pre_pitch_gaze
```

- 返回的是**两个原始分数（logits）**，还没做 softmax；softmax 和「×4−180 解码成角度」在
  `pipeline.py` 里完成。
- 这就是「**ResNet-50 当特征提取器 → 双 90 类分类头**」的完整数据流。

## 11. 关键术语速查表

| 术语 | 含义 |
|---|---|
| 权重 / 参数 | 被训练的数字（主要在卷积核里，还有 BN 的 γ/β、全连接层权重） |
| 卷积核（kernel/filter） | 一个小数字网格（立体盒子 k×k×C_in），滑动求和，1 个核产 1 个通道 |
| 通道（channel） | 图由多张「数字表」叠成；RGB 是 3 通道，特征图是 N 通道 |
| 特征图（feature map） | 一个卷积核滑完图得到的输出，一张表 |
| ReLU | `max(0, x)`，引入非线性 |
| BN（Batch Normalization） | 把每通道拉成均值 0 方差 1，再乘 γ 加 β（γ/β 可训练） |
| 梯度 / 反向传播 | 梯度指示「参数该往哪调、调多大」；反向传播是从输出往回逐层算梯度 |
| 梯度消失 | 梯度连乘趋近 0，前面层学不动；ResNet 的捷径线解决它 |
| 恒等映射 | `y = x`，输出等于输入 |
| 残差 | 差值 `y − x`，网络去学这个「差」而非整个 y |
| 全局平均池化 | 每张特征图取平均 → 1 个数，把 N 张图压成 N 维向量 |
| 1×1 卷积 | 不看周围像素、只对同位置多通道加权，用来改通道数（省算力） |
| 特征提取器（backbone） | 网络前半段卷积层，把像素加工成抽象特征 |

## 12. 参考

- 论文：[Deep Residual Learning for Image Recognition](https://arxiv.org/abs/1512.03385)（He et al., 2015）
- torchvision 实现：`torchvision.models.resnet`（`BasicBlock` / `Bottleneck`）
- L2CS-Net 论文：[arXiv:2203.03339](https://arxiv.org/abs/2203.03339)（ResNet-50 作 backbone）
- 本项目的模型代码：`C:\Users\djj\xd-workspace\projects\L2CS-Net\l2cs\model.py`
