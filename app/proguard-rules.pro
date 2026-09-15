# 工程专用 R8 规则

# kotlinx-serialization 1.11 已在依赖内附带完整的 R8 consumer 规则，包括 Serializable 注解、
# Companion.serializer() 与对象序列化器的保留要求。应用代码又全部显式传入生成的 serializer，
# 因此无需在这里重复保留整个 $$serializer 类及所有成员。

# Room 2.8 的 DAO 实现由 KSP 静态生成，实体通过构造器和属性直接访问，不依赖运行时反射。
# Room 自带的 consumer 规则会保留 RoomDatabase 所需构造器，无需禁止实体类压缩与混淆。

# WebView 未使用 addJavascriptInterface，不需要为 JavaScript 反射入口增加 keep 规则。
