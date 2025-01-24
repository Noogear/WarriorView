# WarriorView - 一款高性能的伤害显示插件
*作者：Noogear*

---
## 📦 插件信息
- **依赖项**: [Packetevents](https://modrinth.com/plugin/packetevents)
- **版本支持**: Paper/Folia 及其分支 1.21+ 


## ✨ 核心功能
1. **动态伤害/治疗数字**  
   - 精确显示伤害/治疗数值, 可自定义显示的小数位
   - 支持根据伤害/治疗类型, 使用不同的文本效果
   - 可利用[MiniMessage](https://webui.advntr.dev)实现颜色以及渐变
   - 支持多种动画效果（需配置`animation.yml`）
   - 文字替换功能, 使用字符替换数字以及文本，可实现定制化效果

2. **多种配置支持**  
   - 文字缩放随机值支持： `0.9,1.0,1.1` 或 `0.9-1.1` 
   - 每一种文本都可以单独配置文本属性选项
  ```
    # 显示的文本, 只能使用minimessage颜色格式
    text-format: <red>-%.2f</red>
    # 文字替换功能
    replacement: ''
    # 生成文字的大小, 可使用单个和多个值或0.9-1.1这种格式, 随机取值
    scale: 0.9, 1.0, 1.1
    # 文字阴影
    shadow: true
    # 视距
    view-range: 1.0
    # 可见玩家的范围, 设置为0时只有玩家本人可见
    view-marge: 16
    # 使用ARGB, 0时为透明
    background-color: 0
    # 可否穿过方块看见
    see-through: true
    # 是否只有玩家相关的才会显示
    only-player: true
    # 数字显示的位置, 可使用: eye, foot. 如果涉及到生物之间的攻击, 还可使用damage代表精确攻击的位置
    position: eye
    # 动画类型
    animation: '1'
    # 移动次数
    move-count: 8
    # 移动间隔, 单位ticks
    delay: 2
```

3. **高性能优化**  
   - 利用发包实现, 异步处理，不会对服务器造成负担
   - 使用的是高版本的Textdisplay显示文本，不会对客户端造成性能影响


## 🛠️ 安装指南
1. 下载 `WarriorView.jar`
2. 放入服务器 `plugins` 文件夹
3. 首次启动自动生成配置：
   ```yaml
   config.yml        # 主配置
   damage_cause.yml  # 伤害原因配置
   regain_reason.yml # 治疗原因配置
   animation.yml     # 动画配置
   replacement.yml   # 文本替换规则
   language.yml      # 语言文件
