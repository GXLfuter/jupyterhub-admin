# 运行时间显示 Bug 修复计划

## 问题描述
在容器管理和在线学生页面中，运行时间字段偶尔会显示 "About a 分钟" 的异常中文，而不是正确的格式（如 "1 分钟"）。

## 问题原因
Docker 容器运行时间在约1分钟时，会返回 "About a minute" 的状态字符串。后端 `ContainerService.java` 的 `convertToChinese` 方法只做了简单的英文单位到中文单位的替换，没有处理 "About a" 这个特殊模式。

## 修复方案
修改 `ContainerService.java` 文件中的 `convertToChinese` 方法，添加对 "About a" 的处理，将其替换为 "1"。

## 修改文件
- `backend/src/main/java/com/jupyterhub/service/ContainerService.java` (第149-164行)

## 修改步骤
1. 在 `convertToChinese` 方法中，添加对 "About a " 的替换逻辑，将其替换为 "1 "
2. 重新编译后端项目
3. 验证修复效果

## 风险评估
- 低风险：仅修改字符串转换逻辑，不影响核心业务功能
- 无需修改前端代码
