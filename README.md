# EndSustain 落幕终焉

Minecraft 1.20.1 / Forge 47+ 的 Boss 与武器模组。

新增「落幕之终焉·末影蘸酱」Boss、终焉之刃武器、终末之路进度系统，并为
Iron's Spellbooks、Yes Steve Model、Goety、Goety Revelation 提供兼容。

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.3.0+ |
| Java | 17 |

### 硬依赖

以下模组为必需项，缺失将无法启动：

- Champions
- Cataclysm
- Legendary Monsters
- Meet Your Fight
- Bosses of Mass Destruction
- Ice and Fire Spellbooks
- Celestial Artifacts
- Curios
- GeckoLib
- Iron's Spells 'n Spellbooks
- Yes Steve Model
- Goety
- Goety Revelation

## 核心内容

### Boss：落幕之终焉 · 末影蘸酱

`endsustain:finale`

具备跨维度阶段推进（末地 → 下界 → 主世界）、魅惑终结技、时间停止、
星辰箭死亡尾杀。掉落终焉核心、锋利/力量 32767 附魔书、终焉之环，
直接放入击杀者背包。

### 终结之刃

`endsustain:endsustain_blade`

伤害与玩家游戏时长线性挂钩：

```
基础白值 = 745700 × 游戏时长（分钟）
```

白值超过 32 位整数上限后进入溢出状态，命中触发终焉强杀——
绕过目标的血量防御，驱动其自身死亡演出与掉落。

### 终末之路

佩戴「小蘸酱」（Curios 向导槽位）后开启的进度系统，
含六种终末技能与十幕故事线。

## 战斗音乐

四音轨对应 Boss 阶段（遭遇 / 战斗 / 终结技 / 死亡尾段）。

**音频文件不入库**（第三方版权素材）。需要自行放置 `.ogg` 到：

```
src/main/resources/assets/endsustain/sounds/music/
```

| 文件名 | 触发场景 |
|---|---|
| `finale_encounter.ogg` | Boss 刚出现 |
| `finale_battle.ogg` | 敌对期（循环） |
| `finale_ultimate.ogg` | 终结技释放 |
| `finale_death_tail.ogg` | 死亡尾段 |

文件缺失时静默跳过，不影响运行。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/endsustain-<version>.jar`。

加密任务 `encryptJar` 会在 `reobfJar` 后自动执行，
对武器相关类做字符串常量加密。

## 项目结构

```
src/main/java/com/endsustain/
├── agent/       外部 agent 保险层（JVM attach + 字节码改写）
├── client/      客户端渲染、界面、音乐
├── combat/      强杀链、终焉结算、死亡判定轴解析
├── compat/      第三方模组兼容（Iron's / YSM / Goety / FTB / Champions）
├── entity/      Boss、投射物、伴侣实体
├── event/       Forge 事件监听
├── item/        物品与武器
├── mixin/       字节码注入
├── music/       服务端音乐状态机
├── network/     网络包与客户端网关
└── progress/    终末之路进度与技能

sim/            离线仿真测试（纯 Java，无需启动游戏）
```

## 许可证

见 [LICENSE](LICENSE)。
