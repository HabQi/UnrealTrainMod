# UnrealTrainMod

一个正在开发中的 Minecraft 1.21.1 / NeoForge 列车模组，支持从 RTM 资源包 ZIP 加载静态 MQO 车辆。

## 安装资源包

模组启动时会在当前游戏或独立服务端的 `mods` 目录内自动创建：

```text
mods/
├─ UnrealTrainMod-<版本>.jar
├─ Packages_RTM/
│  ├─ 中国铁路追加包v1.5.zip
│  └─ 中国铁路追加包[KD25G].zip
└─ Packages_IR/
```

将 ZIP 直接放入 `Packages_RTM`，无需解压、转换或添加 `pack.mcmeta`。支持压缩包根目录直接包含 `assets/` 和 `mods/`，也支持外面再包一层文件夹。`Packages_IR` 仅创建目录，暂未实现读取。仓库 `packages/` 下的目录仅作为本地分析样本，不会自动加载或打包发布；使用时需将包内容按上述结构压缩为 ZIP。

新增、删除或替换 ZIP 后需要重启。F3＋T 会重建已登记车型的客户端模型和贴图，不重新扫描车型配置。独立服务端和客户端必须各自安装相同 ZIP，不自动下载或传输资源包。

## 车辆放置器

- 正式物品 ID：`unrealtrainmod:vehicle_placer`。旧 `example_item` 不迁移。
- 右键空气打开车型列表，支持按车型名称、来源 ZIP 和 ID 搜索；选择后可旋转、平移和缩放预览，点击确认保存选择。
- 右键方块顶面放置所选车辆，按玩家朝向对齐，工具可重复使用；左键模型或手持放置器潜行右键模型可拆除。
- 不再提供内置演示车型。没有有效资源包时显示空列表，新工具在确认车型前不能放置。
- 车辆随世界保存，ID 由原始 `trainName` 确定，ZIP 改名不影响 ID。缺失资源包时保留存档中的模型 ID，以占位线框显示；装回资源包并重启后恢复。相同 `trainName` 按 ZIP 文件名、配置路径排序后保留首项，日志记录冲突。

## 当前支持范围

读取 `mods/RTM/train/ModelTrain*.json` 中的 `trainModel2`、`bogieModel2`、`bogiePos` 及材质配置。车体和转向架显示 MQO 中的可见部分；贴图按配置中的材质名绑定，资源在所属 ZIP 内查找。支持基础颜色、透明度、`AlphaBlend`、单双面和平滑法线。普通 MQO 文件头及 KD25G 使用的 `Compilation` 文件头均可读取。

模型保留原始顶点位置、部件坐标和原点，仅将 MQO 单位按 `0.01` 转为方块单位。转向架在绘制实例时按 `bogiePos` 装配，不改写源顶点。放置不自动居中、抬升或底面对齐；原模型的负 Y 坐标仍然保留。预览通过相机取景。

暂不执行 RTM JavaScript，不生成脚本复制的隐藏车轮，不实现车门动画、灯光状态贴图、音效、行驶、轨道适配、OBJ/MQOZ 或其他 RTM 设施。解析器要求文本顶点和已冻结的几何变换，不修改资源包来修正模型。

RTM 格式适配独立实现，仅对照 [KaizPatchX 中的 RTM 车辆配置](https://github.com/Kai-Z-JP/KaizPatchX/blob/27c0295107da5a16bc0d87319d3542f1f7f64698/src/main/java/jp/ngt/rtm/modelpack/cfg/TrainConfig.java)和[材质绑定行为](https://github.com/Kai-Z-JP/KaizPatchX/blob/27c0295107da5a16bc0d87319d3542f1f7f64698/src/main/java/jp/ngt/rtm/render/ModelObject.java)，未复制其源码。资源包仍适用各自作者的许可。

## 许可证与署名

本项目按 [GPL-3.0-only](LICENSE) 发布，具体版权归属、署名及许可适用范围以各文件声明和 [NOTICE](NOTICE) 为准。
NeoForge MDK 模板原有部分保留其 [MIT 许可证](TEMPLATE_LICENSE.txt)；其他第三方代码、资源及组件适用各自的许可证。
引入或改编第三方内容时，应遵守其许可条件，保留原有版权和许可声明，并注明来源及修改情况。
