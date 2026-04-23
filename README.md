# 反物质湮灭过程 GUI 可视化仿真器

> 模拟前
![image](https://github.com/galihru/AntimatterAnnihilation/blob/main/img/%E5%B1%8F%E5%B9%95%E6%88%AA%E5%9B%BE%202026-04-23%20112538.png)

> 模拟上
![image](https://github.com/galihru/AntimatterAnnihilation/blob/main/img/%E5%B1%8F%E5%B9%95%E6%88%AA%E5%9B%BE%202026-04-23%20112558.png)

> 基于现象学速率方程与阈值判据的教学型交互式仿真工具

## 项目简介

本项目实现了一个用于教学与可视化展示的 GUI 仿真器，用于刻画物质—反物质相互作用、湮灭辐射生成以及相关能量量级的换算与展示。系统重点强调以下内容：

- 反物质的基本概念与物理图像
- 物质与反物质湮灭时的质能转换机制
- 电子—正电子湮灭产生 γ 射线的典型特征
- 对产生阈值与能量判据的直观展示
- 能量、功率、等效当量与事件统计的实时可视化
- 参数扫描与结果导出，便于教学演示与定性分析

> 说明：本项目采用的是**简化的现象学模型**，主要面向教学演示与交互式可视化，并不替代实验级粒子输运模拟、QED 数值求解器或高精度物理实验。

## 背景

### 1. 什么是反物质

反物质是与普通物质对应的一类粒子，其质量相同，但电荷等量反号。  
例如：

- 电子带负电
- 正电子是电子的反粒子，带正电

### 2. 什么是湮灭

当粒子与其对应的反粒子相遇时，二者可以发生湮灭。  
在这一过程中，静质量的一部分或全部可转化为电磁辐射，通常表现为高能光子。

### 3. 为什么释放的能量很大

质能关系由下式给出：

$$
E = \Delta m c^2
$$

其中，$c$ 为光速。由于 $c^2$ 的数值极大，即使很小的质量变化，也可能对应显著的能量释放。

### 4. 电子—正电子湮灭与 511 keV 光子

在中心质心系下，电子与正电子湮灭通常产生两个能量约为 511 keV 的 γ 光子：

$$
e^- + e^+ \rightarrow 2\gamma
$$

### 5. 对产生阈值

高能 γ 光子在适当条件下可产生电子—正电子对。其阈值通常写为：

$$
\gamma + Z \rightarrow e^- + e^+ + Z
$$

并满足：

$$
E_\gamma \ge 2m_e c^2 = 1.022\,\mathrm{MeV}
$$

## 本项目实现了什么

本项目提供一个可交互 GUI，用于展示与分析下列内容：

- 初始物质质量与反物质量设置
- 反应系数、约束强度、场分布梯度、效率与仿真速度调节
- 粒子云演化与湮灭闪光的实时渲染
- 累计能量、平均功率与理论上限的动态显示
- 通道级统计：双光子道、γ+介子道、介子主导道
- 阈值判据与电子对产生事件统计
- 参数扫描结果导出为 CSV
- 精简右侧信息面板，仅保留关键仿真指标

## 数学模型（教学近似）

为保证实时渲染与交互响应，本项目采用简化的现象学速率方程，而非完整的 QED 数值求解。

设：

- $M_m(t)$：物质量
- $M_a(t)$：反物质量
- $M_{\mathrm{ann}}(t)$：累计湮灭质量
- $k$：反应系数
- $\eta$：能量转换效率

则可写为：

$$
\frac{dM_{\mathrm{ann}}}{dt} = k M_m M_a
$$

$$
\frac{dM_m}{dt} = -\frac{dM_{\mathrm{ann}}}{dt}, \qquad
\frac{dM_a}{dt} = -\frac{dM_{\mathrm{ann}}}{dt}
$$

$$
\frac{dE}{dt} = 2\eta c^2 \frac{dM_{\mathrm{ann}}}{dt}
$$

其中，系数 2 表示参与湮灭的物质与反物质质量共同贡献于可释放的静质量能量。

> 该模型用于教学与可视化，不用于实验级定量预测。

![image](https://github.com/galihru/AntimatterAnnihilation/blob/main/img/%E5%B1%8F%E5%B9%95%E6%88%AA%E5%9B%BE%202026-04-23%20112653.png)

## 界面控件说明

![image](https://github.com/galihru/AntimatterAnnihilation/blob/main/img/%E5%B1%8F%E5%B9%95%E6%88%AA%E5%9B%BE%202026-04-23%20112623.png)
- 初始物质量（mg）
- 初始反物质量（mg）
- 可视化粒子数
- 磁场约束强度
- 场分布梯度
- 能量转换效率
- 反应系数 $k$
- 仿真速度
- 开始 / 暂停
- 重置
- 参数扫描导出 [CSV](https://github.com/galihru/AntimatterAnnihilation/blob/main/antimatter_scan_20260423_112703.csv)

## 输出结果如何理解

- **累计释放能量**：从仿真开始到当前时刻的总释放能量。
- **平滑平均功率**：对瞬时功率进行平滑后的趋势指标。
- **理论最大可释放能量**：按当前初始条件和效率估算的上限值。
- **达到理论上限比例**：当前释放能量占理论上限的百分比。
- **场均匀度 / 径向约束 / 稳定度**：用于表征约束空间中的分布质量。
- **通道占比**：显示各湮灭通道的相对贡献。
- **阈值通过率 / 对产生转化率**：反映事件级判据的触发效果。
- **事件速率 / 电子对速率**：反映系统反应强度与动态演化特征。
- **511 keV 光子等效数**：将总能量换算为更直观的粒子数量级。
- **TNT / U-235 / 广岛当量**：用于将焦耳数转换为便于理解的能量尺度。

## 运行方式（Windows）

### 方式 A：VS Code + Code Runner

如果你在 VS Code 中使用 Code Runner，可直接运行当前 `.scala` 文件。  
本工作区已通过 `scala.bat` 做了封装，便于从任意子目录执行。

### 方式 B：scala-cli

```powershell
scala-cli run "D:\你的项目目录\AntimatterAnnihilationGUI.scala"
````

如果 `scala-cli.exe` 不在 PATH：

```powershell
& "D:\工具目录\scala-cli.exe" run "D:\你的项目目录\AntimatterAnnihilationGUI.scala"
```

### 方式 C：使用本项目封装脚本

```powershell
& "D:\你的项目目录\scala.bat" "D:\你的项目目录\AntimatterAnnihilationGUI.scala"
```

### 方式 D：SBT 项目模式

```powershell
sbt run
```

### 运行前检查

```powershell
Test-Path "D:\你的项目目录\AntimatterAnnihilationGUI.scala"
Test-Path "D:\你的项目目录\scala.bat"
Get-Command scala-cli -ErrorAction SilentlyContinue
```

## 参考文献

1. Danielson, J. R., Dubin, D. H. E., Greaves, R. G., & Surko, C. M.
   *Plasma and trap-based techniques for science with positrons*
   **Reviews of Modern Physics** 87, 247 (2015).

2. Hofierka, J., Cunningham, B., Rawlins, C. M., Patterson, C. H., & Green, D. G.
   *Many-body theory of positron binding to polyatomic molecules*
   **Nature** 606, 688–693 (2022).

3. Guessoum, N., Jean, P., & Gillard, W.
   *Relevance of slow positron beam research to astrophysical studies of positron interactions and annihilation in the interstellar medium*
   **Applied Surface Science** 252, 3352–3361 (2006).

4. *Optimizing electron-positron pair production on kilojoule-class high-intensity laser systems*
   **Physical Review E** 79, 066409 (2009).
