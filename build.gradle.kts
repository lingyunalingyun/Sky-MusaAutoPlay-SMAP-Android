plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}

// 沙箱约束：构建产物重定向到工作区（源码留在 F 盘项目目录）
allprojects {
    layout.buildDirectory.set(File("D:/DSH_Works/smap-android-build/${project.name}"))
}
