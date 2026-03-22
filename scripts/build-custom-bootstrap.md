# 自建 Bootstrap 指南

## 方法：使用 termux-packages Docker 构建

```bash
# 1. Clone termux-packages
git clone https://github.com/termux/termux-packages.git

# 2. 修改 scripts/properties.sh 中的包名
# TERMUX_APP_PACKAGE="com.xiaomo.androidforclaw"

# 3. 清理旧缓存
./scripts/run-docker.sh ./clean.sh

# 4. 构建 aarch64 bootstrap
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64

# 5. 输出文件: bootstrap-aarch64.zip
# 6. 放到 extensions/termux/src/main/cpp/bootstrap-aarch64.zip
```

## 为什么需要自建

官方 bootstrap 中所有二进制都硬编码了 `/data/data/com.termux` 路径。
dpkg, apt, bash 等都在编译时把这个路径写入 ELF .rodata section。
由于新路径 (`com.xiaomo.androidforclaw`) 比旧路径 (`com.termux`) 长 15 bytes，
无法在二进制中原地替换。

## 当前状态

- 基础命令（curl, tar, grep, sed, awk, bash）：正常工作（不依赖硬编码路径）
- apt/dpkg：不工作（硬编码路径无法覆盖）
- 需要 Docker 环境来编译
