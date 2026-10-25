# Neko 语言

Neko 是一门用来做编译器实验的玩具 JVM 语言：它有类、接口、`emit`/`scratch`/`nyan` 错误控制机制，
并且直接编译成 Java 字节码。

[English](README.md)

## 项目结构

构建是一个 Gradle 多模块工程。编译器和 IDE 插件是彼此独立的模块，因此用其中一个不会拖入另一个：
插件依赖编译器，而编译器从不反过来依赖插件。

| 路径            | 模块           | 说明                                                      |
|-----------------|----------------|-----------------------------------------------------------|
| `neko/`         | `:neko`        | 编译器：词法、语法、AST、分析器、字节码生成                |
| `plugin/idea/`  | `:plugin:idea` | NekoJ，基于 `:neko` 的 IntelliJ IDEA 插件                 |
| `stdlib/`       | —              | Neko 标准库，由编译器自己编译                             |
| `samples/`      | —              | 示例程序                                                  |
| `neko-compile/` | —              | 编译输出，不纳入版本控制                                  |

`stdlib/` 和 `samples/` 是源码而不是模块：两者都由 Neko 自己编译，所以在那里新增文件不需要改构建。
注意 `stdlib/` **不是**构建传给编译器的源码目录——编译器每次编译都会把打包在自己 jar 里的那份标准库
排在前面。若再把它列为源码目录，每个标准库类都会被声明两次。

## 构建与运行

既然不止一个模块，任务都带模块前缀。

```bash
# 编译配置目录下所有 .neko 文件。
./gradlew :neko:compileNeko

# 运行一个已编译的程序。程序名在构建里查表，所以往 neko/build.gradle.kts 的
# 列表里加一个程序是改配置，而不是新增任务。
./gradlew :neko:runNeko -Pneko.program=ExceptionSample

# 测试。
./gradlew :neko:test                     # 编译器：535 个测试
./gradlew :plugin:idea:test              # 插件：56 个测试，跑在真实 IDE 实例里

# 把编译器打成单个自包含 jar。
./gradlew :neko:executableJar
java -jar neko/build/executable/neko-1.0-SNAPSHOT-all.jar samples --output out
```

## 命令行

`executableJar` 产出 `neko-1.0-SNAPSHOT-all.jar`，只要 JVM 21 及以上，classpath 上不需要任何别的东西：

```bash
java -jar neko-1.0-SNAPSHOT-all.jar <文件或目录>... [选项]
```

参数可以是 `.neko` 文件，也可以是装着它们的目录；目录会被递归搜索。**所有参数都作为同一个程序**
编译——正因如此，一个文件里的类才能引用另一个文件里的类。

```
选项：
  --output <目录>        编译出的 class 文件写到哪里，默认是当前目录。
  --stdlib-path <目录>   改用 <目录> 里的标准库，而不是编译器自带的那份。
                         该目录会被递归搜索 .neko 文件。
  --color, --no-color    强制或关闭彩色诊断输出。
  -h, --help             显示本说明。
```

退出码：成功是 `0`；程序编译失败是 `1`（所有诊断按源码顺序全部打印）；`2` 留给编译器内部错误——
它绝不能被误认为是被编译程序的问题。

### 为什么可执行 jar 是单独的任务

模块自带的 `jar` 依然只是一个普通库 jar。`:plugin:idea` 依赖 `:neko`，并把它的产物打进插件里，
而 IDE 本身已经提供了 Kotlin 标准库——把 `kotlin-stdlib` 和 ASM 塞进那个 jar，就会在插件的
classpath 上多出第二份，正是 `verifyPluginProjectConfiguration` 会拒绝的 bundled runtime 冲突。

所以 `executableJar` 另外写一个**自包含**的 jar，命名也区分开：想要命令行的读者拿这个文件，
插件开发者继续链接原来那个。

依赖是被解包进 jar，而不是靠 manifest 的 `Class-Path` 引用——因为这个 jar 的目的就是拷到另一台机器
上直接能跑。`Main-Class` 才是让 `-jar` 生效的前提：没有它，JVM 不会挑入口点。

### 使用自己的标准库

`--stdlib-path` 让你用自己的标准库，而不是打包的那份：

```bash
java -jar neko-all.jar samples --output out --stdlib-path ./my-stdlib
```

它的用途是改标准库本身，或者给某个项目锁定一个版本。标准库依然排在最前面编译，程序依然不需要
指名它——变的只是源码从哪里来。

提供的标准库是**替换**打包的那份，而不是叠加，这是有意的：锁了旧版的读者不该在不知不觉中又拿到
新版的声明。由此有两条推论，两者都会被明确报出来而不是掩盖过去。路径不是目录、或者里面没有
`.neko` 文件，在命令行上就是错误——悄悄回退到打包版本会让写错的路径看起来像构建成功。而空标准库
就是空的，于是用 `scratch` 的程序会失败并提示「编译器自带的标准库缺失」，这才是实话。

编译出的 class 文件写到项目目录下的 `neko-compile/classes`。这个路径以及被编译的目录都来自
`gradle.properties`：

```properties
neko.sourceDirectories=samples/com/github/kusa233/neko/sample
neko.outputDirectory=neko-compile/classes
```

列出的每个目录都会被递归搜索，所以新增一个源文件就是把它放进某个目录。

`ExceptionSample` 打印：

```
123
456
1
```

`NullableSample` 端到端地演练可空特性，它的注释把**不该**编译通过的行标得和该通过的行一样清楚——
那些错误和输出一样，都是示例的一部分。它最接近[可空类型](#可空类型)的参考文档：

```bash
./gradlew :neko:runNeko -Pneko.program=NullableSample
```

`NyaSample` 对 [`nya`](#检验程序的假设)做同样的事，`ExceptionSample` 对 `scratch`、`nyan` 和
`try` 也是如此。`EffectRecovery` 是同一套机制的参考——也就是下文 `emit`/`scratch`/`nyan` 一节
逐步走完的那个。`EnumSample` 展示 [`miao class`](#枚举miao)——常量、常量可以携带的值，以及
`values()`/`valueOf()`；`PurrSample` 展示[锁](#锁purrpurredpurrclspurredcls)：

```bash
./gradlew :neko:runNeko -Pneko.program=NyaSample
./gradlew :neko:runNeko -Pneko.program=ExceptionSample
./gradlew :neko:runNeko -Pneko.program=EffectRecovery
./gradlew :neko:runNeko -Pneko.program=EnumSample
./gradlew :neko:runNeko -Pneko.program=PurrSample
```

## 标准库

标准库用 Neko 写，放在 `stdlib/`，并且**由编译器携带**：每次编译都会把它排在待编译程序之前。
因此程序永远不需要指名它，调用方也永远不需要传它——`gradlew compileNeko`、命令行、IDE 和测试
什么都不做就能拿到。

它的源码是作为资源打进编译器自己的 jar 的，而不是运行时去读 `stdlib/`。这才让打出的 jar 在另一台
机器上、在插件里（编译器以 jar 形式进入插件的 classpath）、以及在任意目录下的测试运行中都能用——
这三者都不能假定自己和本仓库相邻。`StandardLibrary` 用一个「无论来自 jar 还是来自 class 目录都
解析成同一位置」的路径把它们读回来。

标准库只放语言无法从 JVM 借用的东西：

* `EmitYieldException`——`yield` 抛出的载体。它刻意是 checked 而不是 unchecked：`yield` 只在 `try`
  声明的 handler 内合法，所以中间每个方法都必须声明自己可能把它传出去。这正是让该效应在 class 文件
  里可见、而不是在运行时给人惊喜的原因。它唯一的字段是 `public`——读它的是 `try` 生成出来的
  handler，位于**另一个类**里，写成 `private` 会编译通过，却在第一次真正 `yield` 时抛
  `IllegalAccessError`。

效应机制的其余部分都是生成的：`try` 的 `{ ... }` 块会变成持有该 `try` 的类的一个 `static` 方法，
`scratch` 则编译成对它的一次普通 `invokestatic`。

只有一个内置项不是 Neko 源码：`println`，每种内置类型一个重载——七个数值类型、`string`、`bool`
和 `Object`。它对应 `System.out.println`，并被建模成普通函数，好让分析器和生成器共用同一条解析路径。
`byte`、`short` 和 `char` 在 `PrintStream` 上没有自己的方法，所以这三个用 Neko 类型声明，但按
Java 本来就会把它们拓宽到的 `int` 重载生成。

`for ... in` 遍历用的迭代协议是**JDK 自己的**，不是标准库的：`java.lang.Iterable` 与
`java.util.Iterator`。一个类实现 JVM 已有的这个接口就变得可迭代，这才让 Neko 对象能被当作 Java 的
`Iterable` 使用，也让 Neko 的 `for` 能遍历平台交给它的 `java.util.List`。

`Iterable` 不需要 import，因为它在 `java.lang` 里；`Iterator` 在 `java.util` 里，所以手写
`fun iterator(): Iterator` 的文件要写 `import java.util`。这是平台名字的普通规则，不是给循环开的特例。

`next()` 返回 `Object`，因为那是擦除后的接口所声明的。Neko 擦除泛型时不会生成参数化接口所需的
bridge 方法——`fun next(): int` 会编译成 `()I`，而 `Iterator<T>` 声明的是
`()Ljava/lang/Object;`，两者永远对不上。所以 `Object` 不是绕开 JDK 的权宜之计，它**就是**擦除后的
签名；`for` 负责插入转型和拆箱，循环变量声明的类型依然是循环体读到的类型。

## NekoJ——IntelliJ IDEA 插件

`plugin/idea` 构建 **NekoJ**，为 IntelliJ IDEA 里的 `.neko` 文件提供语法高亮、代码补全和错误提示。

它的核心决定是：什么都不重新实现。插件依赖 `:neko`，直接运行编译器自己的词法、语法和分析器，因此：

- 编辑器里的错误就是编译器的错误，措辞完全一致，并随编译器一起改进；
- 补全列出的是分析器真正解析到的符号，所以名字只有存在之后才会出现；
- 高亮配色来自编译器的 token，原样传递。

不存在第二套会与真正的语法跑偏的文法。

### 构建与安装

```bash
./gradlew :plugin:idea:buildPlugin
```

产物是 `plugin/idea/build/distributions/NekoJ-1.0.0.zip`，用
**Settings → Plugins → ⚠ → Install Plugin from Disk** 安装，然后重启 IDE。

首次构建会从 JetBrains 的 Maven 仓库下载 IntelliJ IDEA 2026.1.4（约 1.6 GB，之后会缓存），
并据此编译插件。版本钉死到具体发行版而不是取最新，是因为三者必须一致：插件编译所依据的 API、
它声明的 `sinceBuild`、以及跑测试用的 IDE。浮动版本会让它们悄悄漂移而没有任何失败。
由于 `sinceBuild` 是 `261`，针对 2026.1 系列最后一个补丁版构建和测试，才让这个声明有意义。

因为平台来自仓库而不是某个本地安装，首次构建需要网络，但它不再依赖机器上装没装 IDE——
CI 上的 runner 能构建出同样的插件。

要注意这是**统一版** IntelliJ IDEA，不是社区版。自 2025.3 起 JetBrains 把社区版和旗舰版合并成
一个产品，所以 2026.1 不再发布 `ideaIC`，插件的 `intellijIdeaCommunity()` 辅助函数也无法解析；
`intellijIdea()` 就是这个合并后的产品，而在这个版本上它是唯一的 IntelliJ IDEA。

有一个后果值得直说，因为它是实实在在的损失：针对合并产品编译，不再像过去针对社区版编译那样
**证明**插件回避了付费专属 API。如今让插件保持诚实的是 `plugin.xml` 里的 `depends`，它恰好只
指名两个模块——`com.intellij.modules.platform` 和 `com.intellij.modules.lang`。缺少任一模块的
IDE 会拒绝加载插件，而不是在运行时以某种更隐蔽的方式失败。

两个模块都以 Java 21 为目标，因为这是 2026.1 平台的要求。

```bash
# 用真实 IDE 校验插件：扩展点、API 用法、兼容性。
./gradlew :plugin:idea:verifyPlugin
```

### 补全提供什么

补全的列表是编译器自己的符号表，不是手写的目录，所以名字恰好在它存在的时候出现：

- **作用域内的局部变量**——插入符上方、所在嵌套块中已经声明的变量，以及所在函数、lambda 或
  `try` 提取器的参数。声明在插入符**下方**的变量不会被列出，因为在那里写它无法编译。
- **所在类自己的成员**——静态体里给出静态成员，实例体里连实例成员一起给出。别的类的成员不会
  裸着列出，因为访问它需要一个接收者。
- **类**——程序自己的类，以及 Java 平台的类。平台的类是从运行中的 JDK 读出来的，而不是硬编码
  列表，并且只给出这个文件真正能指名的那些：`java.lang`，以及该文件已导入的包。
- **类型，在类型位置上**——`:` 之后，以及 `emit [ ... ]` 子句里面。类型位置有自己的一份列表，
  因为变量不是类型。
- **内置类型**，每个都标出它对应的 Java 类型，这样 `Integer` 和 `int` 一眼就能看出是同一个类型，
  而不是两个。

### 自动 import

文件还不能指名的类也会被列出——连同让它可被指名的 `import` 子句，接受补全时替你写好。输入
`Hash`，从弹窗里选 `HashMap`，文件就同时得到 `java.util` 的 import 和那个名字。程序自己的类也一样：
声明在另一个 `locate` 包里的类会被列出并显示它所在的包，按回车就会写进
`import other.pkg.Helper`，让你刚接受的名字真的能编译。

这依赖于三件事保持一致，也正是它这样设计的原因：

- **索引是编译器的。** `PlatformLibrary` 从运行中 JDK 的 `java.base` 模块读出包和类列表，所以
  名字恰好在分析器能够解析它的时候被列出。这里不维护任何表，因为 JDK 会在版本之间增减类。
- **修复方案是编译器的。** 分析器把无法解析的类报成一个携带 `suggestedImport` 的错误，IDE
  把那个字段变成 Alt+Enter 动作。在插件里重新推导该子句，会是第二份实现，而它完全可以和决定
  文件能否编译的那份产生分歧。
- **插入位置由文件决定。** Neko 的文件头是有顺序的——先是 `locate`，然后是所有 `import`，最后
  是类——所以 `NekoImports` 从文件自身的文本推算该子句该放到哪里，而不是靠行号；并且拒绝写入
  文件已经有的子句（重复的 import 是错误）。

需要 import 的类**只在已经输入了前缀之后**才被列出。`java.base` 里有 3373 个类；空前缀下把它们
全列出来既会撑爆弹窗，也会把读者真正想要的那几个名字埋掉。`java.lang` 和已导入的包不需要子句，
所以它们照常列出。

注意语言本身的要求：**import 是强制的**。没有 `import java.util` 的 `HashMap` 是错误而不是警告——
编译器无法从一个从没被告知要去查的包里认识一个类，而悄悄解析它会让该名字的包在源码里隐形。
IDE 负责写这个子句，它并不代替这个子句。同一条规则也适用于程序自己的类：由 `locate` 说了算，
不同包的两个文件就是两个包。

### 无法解析的名字

编译器无法解析的名字是被**染色**的，不只是加下划线。波浪线说的是「这一行有东西不对」；一个红色的
标识符说的是**哪个**名字——在一行有五个名字的时候，这就是提示和答案的区别。

颜色取自平台自己的 `CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES`，也就是 Java 和 Kotlin 用于
同一目的的键，所以 Neko 不需要自己的配色方案，直接继承读者的主题。

决定这件事的是 `Diagnostic.unresolved`，一个由未能找到声明的那个检查设置的标志。它**不**由严重
级别隐含，而这个区分是承重的：「类 'X' 实现了 'Y' 但没有提供 'f()'」是一个锚定在 `X` 上的错误，
而 `X` 是一个解析得好好的名字——把它染红会让读者去修错的东西。只标记真正无法解析的四种形态
（裸名字、被调用的名字、成员名字、类型名字），才让这个染色保持诚实。

### 高亮是怎么做的

有两个细节值得知道，因为两者都不显然，而且都是承重的。

编译器的词法器**丢弃**空白和注释——语法分析器两者都用不上。编辑器做不到：注释必须能着色、能折叠，
而且平台要求相邻 token 无缝铺满整个缓冲区。所以 `NekoHighlightingLexer` 先跑编译器的词法器拿到
它的 token，再把那些空缺重新读成空白、`//` 注释和 `/* */` 注释。编译器产出的东西原样通过。

`NekoParserDefinition` **不是可选的**。平台通过查找 parser definition 来判断一个文件的语言，而
没有它的语言会静默降级成纯文本：没有高亮、没有注解、没有补全，而且任何地方都不会报错说明原因。
空白和注释的 token 集合也是在这里声明的，正是它让编辑器得到真正的 `PsiComment` 和
`PsiWhiteSpace` 叶节点。

语法树本身刻意是扁平的。把文法再写一遍会得到一个可以和编译器的语法分析器产生分歧的分析器，而
IDE 所报告的结构本来就来自编译器，通过 annotator 传来。

## 语法

### 包与 import

文件以一个可选的 `locate` 开头，然后是 `import` 子句，最后是类：

```neko
locate com.github.kusa233.sample

import java.util

class Neko() {
}
```

`locate` 给出文件的包；在其中声明的类会变成 `com/github/kusa233/sample/Neko`。
没有 `locate` 的文件位于无名包。

`import` 指名一个**包**，或者**一个类**——没有逐类别名。import 决定的是：程序**没有**声明的名字
去哪里找。查找顺序是：

1. 程序自己的声明，但**限于本文件自己的包**——两个文件写了相同的 `locate` 就是同一个包，
   谁也不用说什么就能看见对方；
2. 程序自己的声明，位于本文件**已导入**的包里（见下）；
3. `java.lang`，所以 `Exception` 和 `String` 不需要任何子句；
4. 已导入的包，按源码顺序。

第 1 步和第 2 步是同一个查找，中间只隔一个条件，而这条件就是 `locate` 的第二个职责。另一个 Neko
包里的类，在文件导入它之前**不能**直接用简单名写：

```neko
// other/Helper.neko
locate other.pkg

class Helper {
    static {
        var shared: int = 1
    }
}
```

```neko
// Sample.neko
locate my.pkg

import other.pkg.Helper

class Sample {
    static {
        fun main() {
            println(Helper.shared)   // 合法：已导入
        }
    }
}
```

少了那行 `import`，名字就解析不到，而编译器会明确说出该写什么：

```
Sample.neko:6:21: error: unresolved reference 'Helper': it is declared in another package this file
does not import — write 'import other.pkg.Helper' to reach it
```

这条诊断带着 `suggestedImport`，所以在 IDE 里按 Alt+Enter 就会替你把子句写上——平台类一直用的就是
这套机制，现在也覆盖程序自己的类。

**限定名**不需要 import，因为把包写出来*本身*就是在说明名字来自哪里；上面那个文件里写
`other.pkg.Helper.shared` 不加任何子句也能用。

两种写法语法相同，靠名字**实际是什么**来区分，而这只有平台能回答：

```neko
import java.util          // 一个包：其中的任何类，按简单名
import java.util.HashMap  // 一个类：钉住 HashMap，别的包不得再提供这个名字
```

没有 `.*`，这是刻意的。通配符会是「裸包导入」的第二种说法，所以它被拒绝，并告诉你该写成什么：

```
Main.neko:3:19: error: Neko has no '.*': 'import java.util' already imports everything in 'java.util',
so write 'import java.util'
```

对于 JDK 描述的包，类必须真的在里面：`import java.util` 不会让 `BufferedReader` 可解析，因为
`java.util` 里没有这个类。这既防止打字错误变成指向不存在类的引用，也正是编译器能告诉你该写哪个
子句的原因：

```
Main.neko:8:20: error: unknown type 'HashMap': 'HashMap' is java.util.HashMap, which this file does
not import — write 'import java.util' to reach it
```

JDK 不描述的包——在别处编译的——完全无法检查，所以按文件自己说的算。这本来就是 import 存在的理由。

### 限定名

名字也可以内联带上包名，完全不用 `import`：

```neko
h: java.util.HashMap = java.util.HashMap()
start: long = java.lang.System.currentTimeMillis()
out: Object? = java.lang.System.out
```

类型里可以，变量的声明类型里可以，作为调用或字段读取的接收者也可以。它是 import 无法表达的两种
情况的出口：文件根本不想导入的那个包里的类，以及两个包都声明的同一个简单名。

`a.b.C` 和 `value.member` 是同一套语法，所以编译器先解析再判断：只有当确实存在一个叫这个名字的类
时，点链才是限定类名。其余情况——`p.x`、`Code.OK`、`list.size`——仍然是成员访问。链的根决定了这一点：
以程序自己声明的类开头的链是静态成员读取，永远不是包。

限定名遵循与 import 相同的权威规则。JDK 描述的包里必须真有这个类，所以打字错误会被抓出来：

```
Main.neko:6:29: error: 'java.util' has no class named 'Hashmap'
```

JDK 不描述的包按文件自己说的算，因为在别处编译的类无法检查，拒绝它会让限定名在它本该解决的场景里
完全没用。

JDK 类的**静态**成员通过反射解析，并按反射出的 owner 和 descriptor 派发；没有一个重载匹配的调用
会被拒绝，并告诉你该类到底声明了什么。类名在接收者被当作值之前就先匹配，因为那里根本没有值——而且
既然 Neko 没有静态导入语法，`Cls.member` 就是访问静态成员唯一可用的写法。

但只有静态成员：JDK 类的**实例**在这里没有成员。`System.currentTimeMillis()` 和 `System.out` 可用，
而对 `HashMap` 调 `m.size()` 会被拒绝——程序从未编译过 JDK，所以没有符号可供逐一检查成员，消息会
直接说明这一点而不是猜测。平台交出来的值可以当作值使用：传出去、存起来、打印。

### 决定重载的规则

给一个基本类型的实参挑平台方法的重载时，只有参数类型**完全相同**才算匹配，因为 Neko 没有隐式数值
拓宽：`Thread.sleep(1)` 不是 `sleep(long)` 的实参，会被报错并列出该类真正的重载，于是读者知道要写
`1L`。一个基本类型只有在参数正好是它**自己的装箱类**时（`int` 对 `Integer`）才能传给引用参数；
其他引用参数只接受引用实参。

这条规则不是挑剔，它修的是一个真实缺陷。`Thread` 同时声明 `sleep(long)` 和 `sleep(Duration)`，
而反射返回重载的**顺序并不固定**：JDK 21 把 `sleep(Duration)` 排在前面，JDK 25 把 `sleep(long)`
排在前面。在「引用参数接受任何实参」的旧规则下，`Thread.sleep(1L)` 会在 JDK 21 上选中
`sleep(Duration)`、在 JDK 25 上选中 `sleep(long)`——同一个程序，两个不同的描述符。选错的那个把
`long` 压进声明为 `Duration` 的位置，于是写 class 时计算栈映射帧就对不上了，在 ASM 的
`Frame.merge` 里抛出 `ArrayIndexOutOfBoundsException`，表现为「编译器内部错误」。一段十行的程序、
只在一个 JDK 上崩，原因就在这里。按不依赖反射顺序的规则选重载，编译结果才能在各个 JDK 上都可复现。

### 类

```neko
class Neko() {
}
```

### 函数（方法）

成员函数：

```neko
class Neko() {
  fun test() {
  }
}
```

静态成员：

```neko
class Neko() {
  static {
    private val counter: int = 0

    fun testStatic() {
      counter = counter + 1
    }
  }
}
```

`static { ... }` 块同时容纳字段和函数，块里的每个成员都是静态的——这正是这个块所表达的意思，所以
在成员上再写一次 `static` 是错误：

```neko
class Neko() {
  static fun testStatic() { }   // 错误：静态成员应当写在 'static { ... }' 块里
}
```

可见性写在成员上（`private val`、`public fun`），不写在块上。块不能嵌套，也不能包含
`constructor`：构造函数创建的是实例，所以它永远不是静态的。在块外面声明的成员是实例成员。

### Lambda

Lambda 写作 `{ 参数 -> 主体 }`，凡是期望函数类型或单抽象方法接口的地方都能写：

```neko
f: [int] -> int = { x ->
  yield x + 1
}
```

参数类型能从期望类型推断时可以省略注解；推断不出来时，每个参数都必须写类型。

#### 期望单抽象方法接口时

Lambda 也可以充当「只声明一个抽象方法」的接口——也就是 Java 说的函数式接口——因此能直接写进
调用里：

```neko
interface Action {
  fun perform(times: int)
}

class Main {
  static {
    fun twice(a: Action) {
      a.perform(2)
    }

    fun main() {
      twice({ n ->
        println(n)
      })
    }
  }
}
```

接口同时提供参数类型和返回值，和函数类型的作用完全一样，因此主体是按 `perform` 的签名检查的。
在字节码层面，lambda 变成一个实现该接口的类，定义的是**接口自己的**方法——这里是 `perform`，
`Runnable` 就是 `run`——而不是统一的 `invoke`。

不需要写任何东西来「要求」这件事：参数声明的类型就是目标。声明了两个及以上抽象方法的接口不能作为
目标，因为无法说明主体属于哪一个。

Java 平台自带的接口同样可用，包括写进程序从未声明过的平台构造函数里：

```neko
// `Thread(Runnable)`：从 Thread 那些同样只有一个参数的重载里挑出它，靠的是「lambda 只能塞进
// 函数式接口参数」这一条——没有靠猜。
t: Thread = Thread({
  println("thread body")
})
```

生成的类实现 `java.lang.Runnable`、定义 `run`。方法名取自接口本身而不是编译器的约定，这才让它是一个
真正的 `Runnable` 而不只是「看起来像」。要注意 `Thread` 自己的方法仍然不能从 Neko 调用——调用平台
类型的实例方法是另一件事，也是更早就存在的限制，`"abc".length()` 同样会失败。

#### 没有参数时 `->` 可以省略

没有参数的 lambda 没什么需要 `->` 分隔，所以 `{ ... }` 就够了：

```neko
fun main() {
  performIt({
    println("没有箭头")
  })
}
```

判断依据是**在匹配的 `}` 之前是否出现 `->`**，而不是猜内容。`{ }`、`{ println(1) }` 和
`{ -> println(1) }` 是同一个 lambda，只有最后一种明确写出了「它不接收任何东西」。主体有多条语句时
照常写即可——多余地套一个箭头，正是这种短写法要消除的东西。

### 值类（`meow`）

`class` 前面的 `meow` 在头部声明字段，并据此生成 `equals`、`hashCode` 和 `toString`：

```neko
meow class Point(val x: int, private val y: int) {
}
```

- 头部列表就是字段列表。每一项是 `[private|protected|public] val|var 名字: 类型`，用逗号分隔，
  也可以一个都没有：`meow class Unit()`。
- 两个实例在「同一个类且每个字段都相等」时相等。**私有字段也参与**，和 Kotlin 的 data class 一样。
- 两个 meow 值之间的 `==` 和 `!=` 使用生成的 `equals`。`a.equals(b)`、`a.hashCode()` 和
  `a.toString()` 也一样：这三个都是可以正常调用的普通方法。
- `toString` 打印类名和按声明顺序排列的每个字段，`string` 字段带引号：

  ```neko
  println(Point(1, 2))     // Point(x=1, y=2)
  println(Cat(1, "kusa"))  // Cat(age=1, name="kusa")
  ```

- 自己写 `equals`、`hashCode` 或 `toString` 会取代生成的那个。

头部会被展开成语言本来就有的东西——一个字段、一个构造函数参数和一句 `this.x = x`——所以只有那三个
生成的方法是新东西。它们直接以字节码生成，因为 Neko 没有 `is` 运算符也没有转型，这三个方法没有一个
能用 Neko 写出来。

`meow` 不能用于 `interface`（接口没有字段可供推导），而且 meow 类必须写出它的字段列表。

### 枚举（`miao`）

`class` 前面的 `miao` 声明一组固定的具名值：

```neko
miao class Color {
    RED,
    GREEN,
    BLUE
}
```

- 类体就是常量列表，一个常量一个名字，顺序就是 `ordinal()` 报告的顺序。常量之间用逗号分隔，
  可选的 `;` 在任何函数之前结束这个列表。
- **class 文件是真正的枚举**：它继承 `java.lang.Enum`、带有 `ACC_ENUM`、有一个 `$VALUES` 数组，
  并声明 `values()` 和 `valueOf(string)`。所以 `name()`、`ordinal()`、`compareTo`、`equals`、
  `hashCode` 和 `toString` 全都来自 `java.lang.Enum`，行为符合 Java 调用者的预期——
  `println(Color.RED)` 打印 `RED`，而不是一个对象标识。
- `values()` 返回常量数组的**副本**，所以往结果里写东西无法破坏这个枚举。
- 常量可以携带值，此时字段写在头部，写法和 `meow class` 完全一样，每个常量各自提供自己的值：

  ```neko
  miao class Code(val status: int) {
      OK(200),
      GONE(410)
  }

  println(Code.OK.status)    // 200
  ```

- 两个常量之间的 `==` 是引用同一性，这正是枚举能当作一组标签使用的原因：每个常量只有一个实例。

枚举不能声明构造函数——常量是它仅有的实例，由编译器根据常量列表创建。它不能继承另一个类，因为
`java.lang.Enum` 已经是它的超类。它不能覆盖 `name`、`ordinal`、`equals`、`hashCode` 或
`compareTo`，这些被 `java.lang.Enum` 声明为 `final`；`toString` 则是开放的。列表必须至少指名
一个常量。

### 锁（`purr`、`purred`、`purrcls`、`purredcls`）

`purr` 用一个锁保护一段语句，并且无论以何种方式离开这段代码都把锁还回去：

```neko
class Counter() {
    var total: int = 0

    fun add(amount: int) {
        purr {
            current: int = this.total
            this.total = current + amount
        }
    }
}
```

这个锁是 `java.util.concurrent.locks.StampedLock`，而这正是它成为关键字而不是库调用的原因：释放
必须针对**每一条**离开该块的路径生成——自然走完、`return`、`nyan`、`yield`，以及块内所调用的任何
东西抛出的异常——而且 `unlock` 还需要获取时返回的 stamp，那是一个源码无法携带的 `long`。
`synchronized` 块既不需要这两者，但它也无法提供读锁。

这里有两个彼此独立的选择——用**哪个**锁、以及**哪种**模式——每种组合都有自己的词：

| 写法 | 锁 | 模式 |
| --- | --- | --- |
| `purr { ... }` | 对象的（`this`） | 独占 |
| `purred { ... }` | 对象的（`this`） | 与其他读者共享 |
| `purrcls { ... }` | 类的 | 独占 |
| `purredcls { ... }` | 类的 | 与其他读者共享 |

类锁是整个类一把锁，由每个对象和每个 `static` 函数共享，所以 `purrcls { }` 相当于
`synchronized (Cls.class)`，也是 `static` 函数必须用的——它没有 `this` 可以挂实例锁。

- `purred` 和 `purredcls` 允许若干线程同时进入，所以它们用于读取一个期间不许别人改动的值。
  读-改-写应当放在 `purr` 下：两个读者按设计会同时持有读锁，因此这样的块并不独占，它的更新会丢失。
- **一个线程不能对同一把锁取两次。** `StampedLock` 在任何模式下都不可重入，所以
  `purr { purr { ... } }` 会永久阻塞。换模式也没用：在 `purr` 里面的 `purred` 依然是第二次获取
  同一把锁。所有这些嵌套都会在程序被分析时拒绝，而不是留到运行时挂死。
- 两把锁是不同的锁，所以 `purrcls { purr { ... } }` 合法，它正是「以类为范围保护逐对象工作」的样子。
- 前后相继的块**不**构成嵌套：进入第二个之前第一个已经释放。

用四个词而不是一个关键字加一个修饰语，是因为 `class` 和 `read` 在这门语言里都是普通标识符——
在一个位置把它们读作修饰语、在其他所有地方读作名字，会形成读者看不见的上下文相关词法。

锁是按需创建的——一个从不写这些关键字的类根本不带锁字段。实例锁是每个对象的一个字段，类锁是一个
`static` 字段，两者都由编译器创建，因为 `StampedLock` 住在 `java.util.concurrent.locks` 里，
Neko 源文件无法指名它。

嵌套规则只**在一个方法体内**检查，因为那是分析器能看到的范围。一个方法取了锁、又调用另一个取同一把
锁的方法，会挂死；把这种情况区分开是程序员的责任。

### 可空类型

类型后面加 `?` 表示它也可以是**缺席**的。`T?` 是 `null` 唯一能赋值的类型，而这条规则就是整个特性：
一个可能缺失的值在它的类型里说明了这一点，而所有会解引用它的地方都在编译期被检查。

```neko
name: string? = null
name = "kusa"              // 真实值总是允许的
println(name == null)      // 询问它是否缺席

count: int? = 1            // 可空基本类型持有装箱的 Integer
missing: string = null     // 错误：'null' 需要可空类型
```

- **赋值。** 非空赋给可空是允许的；可空赋给非空是错误。实参、返回值和数组元素同理。
- **解引用。** 可空值不能读取、不能调用、不能索引、不能透过它赋值。当 `obj` 或 `arr` 可空时，
  `obj.field`、`obj.f()` 和 `arr[i]` 都是错误，因为它们中没有一个能替缺席的情形作答。一个把问题
  定下来的测试会为它所覆盖的代码收窄该值，所以在分支内部普通的写法就能用——见下面的**智能转换**。
- **`?.`** 是使用可空值的方式：`h?.value` 是这个字段的值，若 `h` 为 null 则为 `null`；
  `h?.f(...)` 只在 `h` 存在时才调用 `f`——不存在时**实参不会被求值**。结果是可空的，所以安全调用
  可以串起来：`a?.b?.c`。
- **`?(...)`** 对*函数值*做同样的事。`?.` 保护的是成员被访问时所经由的那个对象，但存放在变量里的
  函数没有这样的对象：被调用者本身才是可能缺席的东西。`f?(1, 2)` 只在 `f` 存在时调用它，不存在时
  给出 `null`，同样不求值实参。

  ```neko
  present: ([int, int] -> int)? = { x, y -> yield x + y }
  absent: ([int, int] -> int)? = null

  println(present?(1, 2))     // 3
  println(absent?(1, 2))      // null —— 而且调用根本不会发生
  println(adder()?(1, 2))     // 3：被调用者就是那次调用给出的值
  ```

  和 `?.` 一样，任何 `?` 都让结果可空，因为没发生的调用没有结果——即使被调用者本身不可能缺席也
  如此。所以 `a: int = f?(1)` 是错误，而 `a: int? = f?(1)` 不是。不加 `?` 的话，可空的函数值
  根本无法被调用。对一个**不可能**缺席的调用加 `?` 会被拒绝而不是忽略：声明的函数按名字解析
  （`f?(1)`）、成员函数（`obj.f?()`）也是如此、类名则是构造（`Cls?()`），所以每种情况下都没有
  东西需要保护，普通写法才是唯一有意义的写法。
- **比较。** `x == null` 和 `x != null` 是询问缺席的方式，而 `==` 在 null 操作数上从不抛异常：
  它编译成 `java.util.Objects.equals`，对两个 null 给出 `true`。可空基本类型按值比较，所以
  `count == 1` 对 `int?` 也能用。
- **承载。** 可空基本类型在 class 文件里是 Java 引用类型——`int?` 就是 `java.lang.Integer`——
  这才让缺席的情形能够被表示。这一点在源码里完全不可见。

写 `T??` 是错误：可空的可空和可空是同一个类型。

函数类型以它的返回类型结尾，所以加在它上面的 `?` 会附着到返回类型而不是函数上：`[int] -> int?`
返回一个可空的 `int`。要表达**函数值**可能缺席，就把它括起来——`([int] -> int)?`——而这就是
`f?(...)` 调用时所用的写法。两者是不同的类型，关于其中一个的消息从不会引用另一个的写法。

`null` 从它的上下文读取，所以它需要一个上下文：`x: string? = null`，以及 `f` 声明了一个参数时的
`f(null)` 都可以，而 `println(null)` 不行——那些重载没有给出唯一的参数类型可供读取，消息会直接说明
这一点而不是猜测。先给它一个名字，它就会打印成 `null`。

`NullableSample.neko` 演练了以上全部，包括那些不该编译通过的行。

### 智能转换

一个把「可空值是否存在」定下来的测试，会为它的答案所覆盖的代码**收窄**该值。还是同一个值、同一个
声明；分析器只是对某个区域知道得更多，所以在那里普通的写法就能用，不需要 `?`。

```neko
class Holder {
    val value: int

    constructor(value: int) {
        this.value = value
    }
}

h: Holder? = Holder(4)

if (h != null) {
    println(h.value)       // 4 —— 这里 h 是 Holder，所以不需要 `?.`
} else {
    println("absent")
}

if (h == null) { return }  // 或者离开：接下来的代码就是测试的另一侧
println(h.value)           // 也没问题
```

- **哪些测试。** 只有 `== null` 和 `!= null`，与 `!`、`&&`、`||` 组合。`x != null && x.f` 成立，
  因为第二个操作数只在第一个成立时才被求值；`x == null || x.f` 因镜像的理由成立。`if` 的每个分支
  看到的是**它自己的**条件所证明的东西：`x != null` 的 then 分支和 `x == null` 的 else 分支都
  知道值在那里。`nya` 的失败块是要小心处理的情形——它既回应抛出、也回应 false 的答案，所以它只
  得到**不会抛出的条件**的事实；见[检验程序的假设](#检验程序的假设)。
- **哪些值。** 局部变量或参数——包括 `var`，因为它的函数外面没有东西能写它——以及 `val` 字段，
  无论透过对象读取（`point.x`）还是在类内部裸写。`var` 字段永不收窄：它是任何持有该对象的东西都
  可以写的一处地方。
- **有效多久。** 直到该声明被写入。`h = other` 会丢弃这个事实，在某条**可能**执行过的分支里写入
  也一样。事实不会活过证明它的那个分支，所以 `if` 之后的读取又变回可空。
- **可作为收窄后的类型使用。** 在分支内部，这个值可以用在任何需要非空类型的地方——读取、调用、
  索引、赋给一个 `T` 变量、传给一个 `T` 参数、从一个声明 `T` 的函数返回——因为在那条路径上它
  确实就是一个 `T`。离开分支，它又是 `T?`。

对 `int?` 来说这不止是写法的变化：值是以装箱方式存放的，所以收窄后的读取在使用前会被拆箱，这才让
`n + 1` 做加法、`n == 7` 按值比较。

收窄只在测试已经回答了问题的地方免去 `?.`。未经测试的值仍然要用 `?.` 读取，也正是这一点让安全
写法成为始终可用的那一种。

### 内置类型

基本类型就是 Java 的那八个，拼写按 Neko 的方式：

| Neko        | JVM 描述符 | Java 引用类型          |
|-------------|------------|------------------------|
| `byte`      | `B`        | `java.lang.Byte`       |
| `short`     | `S`        | `java.lang.Short`      |
| `char`      | `C`        | `java.lang.Character`  |
| `int`       | `I`        | `java.lang.Integer`    |
| `long`      | `J`        | `java.lang.Long`       |
| `float`     | `F`        | `java.lang.Float`      |
| `double`    | `D`        | `java.lang.Double`     |
| `bool`      | `Z`        | `java.lang.Boolean`    |
| `string`    | —          | `java.lang.String`     |
| `Object`    | —          | `java.lang.Object`     |
| `Throwable` | —          | `java.lang.Throwable`  |

每当基本类型必须是一个 `Object` 时——在 `yield` 抛出的 `Object` 里面、在可空局部变量里，或者作为
被 JVM 擦除的类型参数——它就按表中那个 Java 引用类型承载。

因为这些 Java 类型就是值实际变成的东西，所以它们也作为类型名被接受：

```neko
count: Integer = 1        // 与 `int` 同一个类型
flag: boolean = true      // 与 `bool` 同一个类型
big: Long = 1             // 与 `long` 同一个类型
```

两种拼写是**一个类型**，而不是两个需要互相转换的类型：同一作用域里 `value: string` 和
`value: String` 属于重复声明，而 `Integer` 可以用在任何需要 `int` 的地方。在 IDE 里，`:` 之后的
补全会给出内置类型并标出每个对应的 Java 类型，所以 `Integer` 和 `int` 一眼就能看出是同一个东西。

### 数值规则

Neko **不在具名类型之间做隐式转换**，这包括数值类型之间：它没有 Java 那种拓宽。`x: long = someInt`
是错误，`int + long` 是错误，而 `long + long` 才是 `long`。算术和比较要求两个操作数是**同一个**
数值类型，结果也是那个类型——类型之间的切换写在源码里，而不是由编译器推断。

**字面量**是唯一的例外，因为它在被上下文赋予类型之前没有自己的类型：

```neko
small: byte = 1           // 读作 byte
big: long = 1             // 读作 long
total: long = 1 + 2       // 两个都读作 long，所以是 long + long
```

放不进上下文所要求类型的字面量会在字面量本身报错并给出范围，而不是被悄悄收窄：

```neko
tooBig: byte = 300        // 错误：放不进 'byte'，其范围是 -128..127
```

### 字符串拼接

当**任意一侧**是 `string` 时，`+` 就是拼接，另一侧可以是任何类型：

```neko
n: int = 42
println("n=" + n)             // n=42
println(n + " is the answer") // 42 is the answer
println("ok? " + true)        // ok? true
println("c=" + 'x')           // c=x
println("at " + 1L)           // at 1
```

`string + string` 是同一条规则在两侧都是字符串时的情形。结果永远是 `string`，这正是链式拼接能
继续成立的原因：`"x" + 1 + true` 里第一个 `+` 已经产出 `string`，所以第二个也是拼接。
想先做算术就加括号——`"sum=" + (1 + 2)` 打印 `sum=3`。

这不是把数值规则放宽了。`1 + 2` 依然是算术、依然是 `int`；两侧都没有字符串的 `+` 完全不受影响，
所以 `s: string = 1 + 2` 仍然是错误——报的是初始化式的类型与声明不符，这才是真正的错误。

类类型的值通过 `String.valueOf` 加入，所以它自己的 `toString` 决定打印成什么。缺席的值打印
`null`，而 `null + null` 是错误：两侧都没说这个值是从什么类型缺席的，也就没有字符串可供拼接。
不产出值的调用同样不能拼接——没有东西可以追加。

### 字面量

| 写法            | 类型                              |
|-----------------|-----------------------------------|
| `1`             | `int`，或上下文所要求的类型        |
| `1L`            | `long`                            |
| `1.5`           | `double`                          |
| `1.5f`, `1.5F`  | `float`                           |
| `'a'`           | `char`                            |
| `"text"`        | `string`                          |
| `true`, `false` | `bool`                            |
| `null`          | 上下文所要求的可空类型             |

后缀才是命名小数类型的东西：`1.5` 是 `double`，而 `float` 更窄，所以 `x: float = 1.5` 是错误
而不是静默收窄——写 `1.5f`。字符字面量接受和字符串一样的反斜杠转义，所以 `'\n'` 是一个字符。

`null` 是特殊的那个：它不命名任何值，所以它没有自己的类型，而是按上下文所要求的可空类型来读。
没有这种上下文的 `null` 是错误而不是猜测——见[可空类型](#可空类型)。

### 局部变量

省略类型时会从初始化式推断局部变量的类型：

```neko
instance = Instance()   // instance 是一个 Instance
count = 1               // count 是一个 int
```

写出类型仍然允许，也仍然会被检查。裸写的 `name = value` 在作用域内没有同名东西时是**声明**，
有则是**赋值**——所以 `str = "new value"` 仍然写向上面声明的那个 `str`，正如 `Sample.neko` 所示。
两者仅凭作用域区分，这也是由编译器而不是语法分析器决定的原因：一个裸名字也可能是同一个类里
声明在更下方的字段。

推断出的局部变量是可变的，而且它的名字只在它的值被分析**之后**才登记，所以作用域内没有 `count` 时
写 `count = count + 1` 报的是引用错误，而不是悄悄声明一个从自身开始的变量。

### 循环（`for`、`for ... in`）

`while` 是 Java 的，原样不变：

```neko
while (count < 10) {
  count++
}
```

`for` 有两种写法。三段式的也是 Java 的：

```neko
for (i: int = 0; i < 100; i++) {
  println(i)
}
```

三段都可以省略，所以 `for (;;)` 是无限循环——它没有可失败的测试，这正是函数体以它结尾的函数不需要
`return` 的原因。步进可以是 `++`、`--`，或一次赋值：`for (i: int = 1; i < 40; i = i * 3)` 是
同样性质的三段。

另一种写法遍历一个序列，`in` 是区分两者的那个词：

```neko
for (element: int in values) {
  println(element)
}
```

可以遍历两种东西：

* **数组**，直接走。它不需要接口，因为 JVM 不给数组任何接口——编译器把长度读取和元素加载内联生成。
* **实现了 `java.lang.Iterable` 的类**——JDK 的接口，不是 Neko 的。它的 `iterator()` 必须返回
  `java.util.Iterator`，所以这样的文件写 `import java.util` 而不是 `import java.lang`。`next()`
  返回 `Object` 而不是类型参数，因为 Neko 擦除泛型时不会生成参数化接口所需的 bridge 方法——所以
  擦除后的形态必须**就是**声明的那个形态。循环变量自己的类型依然生效：编译器插入转型（基本类型还会
  拆箱），所以 `for (e: int in numbers)` 读到一个 `int`，而程序本身没有写任何转型。

无论哪种形式，循环变量都是**该循环私有的**、且**只读的**。私有，所以一个方法里的两个循环可以各自
声明 `i`，之后都不可见。只读，因为下一轮会重新绑定它：赋值会被算出来然后丢弃，所以直接拒绝。

`++` 和 `--` 是**语句，不是表达式**。它们不产出值，所以 `x = i++` 写不出来，两者也都不能出现在
条件里。它们作用于整型变量——`int`、`long`、`short`、`byte`——以及透过接收者访问的字段
（`holder.count++`）。`byte` 和 `short` 在写入时会收窄回去，和对它们的赋值完全一样。

### 入口点

```neko
class Main {
  static {
    fun main() {
      println("no arguments needed")
    }
  }
}
```

`static` 的 `main` 可以接受 `string[]`，也可以什么都不接受。JVM 找的正是 `main(String[])`，所以
省略参数列表时编译器会生成一个调用所声明函数的桥接方法。

### 错误控制

声明了 `emit` 子句的函数，等于说自己可能把控制权交给某个 `try` 的 `{ ... }` 块。**`scratch` 进入
那个块，并带着一个答案回来**——它是一次普通调用，不是离开。该块被编译成一个 `static` 方法，所以
`scratch` 就是一次 `invokestatic`：

```neko
class Neko() {
  fun invoker() {
    // 调用一个 `emit` 函数总是需要 `try`，哪怕它从不 scratch：子句才是命名 handler 的东西，
    // 而只有 `try` 会声明 handler。
    v: int = try test() { emit1, emit2 ->
      println(emit1 * emit2)
      // `nyan` 回应的是进入此块的那个 `scratch` 点。
      nyan emit1 + emit2
    }
    println(v)
  }

  fun inheritEmit() {
    // 带*相同*子句的函数可以原样把这次离开传出去，所以它不需要 `try`。
    test()
  } emit [int, int]

  fun test(): int {
    // 向上两层到达那个块，并值它给出的任何答案。
    return scratch 123, 456
  } emit [int, int]
}
```

用作值的 `try`，在调用正常返回时就值那次调用返回的东西。`nyan` 的答案回到的是 `scratch` 点，
而不是 `try`——所以两者是独立的：

**声明了 `emit` 子句的函数不一定要真的 scratch。** 子句描述的是**调用方**必须准备的另一条出路；
这个函数体是否真的走那条路是它自己的事，从不走的主体总是正常返回：

```neko
class Neko() {
  fun test(): int {
    return 7          // 从不 scratch，这没问题：子句说的是调用方可以期待什么。
  } emit [int]

  fun main() {
    // 调用仍然需要 `try`：万一真的走到了 handler，那才是声明它的地方。
    v: int = try test() { x ->
      nyan x
    }
    println(v)        // 7
  }
}
```

这条子句只在一个方向上被检查。`scratch` 需要的是一处可到达的 handler，而全程序都没有 handler 的
`scratch` 会报错——否则代码生成不知道要调用哪个方法。反过来，没有 `scratch` 支撑的子句不算错。

```neko
class Neko() {
  fun invoker() {
    // 调用返回 0，所以这里是 0 —— handler 的 `nyan` 去了 `scratch` 点。
    result: int = try test(false) { emit1, emit2 ->
      nyan emit1 * emit2
    }
    println(result)
  }

  fun test(flag: bool): int {
    if (flag) {
      v: int = scratch 123, 456
      return v
    } else {
      return 0
    }
  } emit [int, int]
}
```

**`yield` 则是透过 `try` 离开。** 它抛出携带其值的 `EmitYieldException`，`try` 捕获它，那个值就
成为 `try` 所值的东西——所以无论 `scratch` 在多深的地方，它都能到达那个 `try`：

```neko
class Neko() {
  fun invoker() {
    // `scratch` 在 `try` 下面两层，而 handler yield 出的答案依然是这个 `try` 所值的东西。
    result: int = try outer() { emit1, emit2 ->
      yield 7
    }
    println(result)   // 7
  }

  fun outer(): int {
    return inner()
  } emit [int, int]

  fun inner(): int {
    return scratch 1, 2
  } emit [int, int]
}
```

`yield` 是 handler **运行时**做的事，所以它需要一个会运行的 handler：调用从不 scratch 的 `try`，
值的是那次调用返回的东西，handler 里的 `yield` 根本不会被执行到。

```neko
class Neko() {
  fun invoker() {
    // `test` 返回 0 且从不 scratch，所以 handler 不会运行：这里是 0，不是 7。
    result: int = try test() { emit1, emit2 ->
      yield 7
    }
    println(result)
  }

  fun test(): int {
    return 0
  } emit [int, int]
}
```

这三个关键字不可互换，而它们的差别就是整个设计：

| 写法 | 到达 | 把值带到 |
| --- | --- | --- |
| `scratch v1, v2` | `emit` 子句匹配的那个 handler | 它自己的位置，作为它自己的结果 |
| `nyan v` | 离开 handler | 进入它的那个 `scratch` 点 |
| `yield v` | 通过抛出离开 | 声明该 handler 的那个 `try` |

`scratch` 仅凭它的 `emit` 签名匹配 handler，所以**每个签名在整个程序里只能有一个 handler**——
两个从调用点的角度看是无法区分的，这一点会被报出来，而不是按声明顺序解决。

#### 省略值

`nyan v` 和 `yield v` 里的 `v` 可以省略，而**哪种形式正确取决于写在哪里**：handler 应答的是到达它
的那些 `scratch` 点，所以值能不能带出去由那些点决定，而不是由 handler 自己的偏好决定。

```neko
class Neko() {
  fun risky(): int {
    return 1 / 0
  }

  fun guard() {
    // 这里没有任何东西读取答案，所以下面那个 handler 是 `void` 的。
    try risky() scratch 5
    println("after")
  } emit [int]

  fun invoker() {
    try guard() { x ->
      // 裸 `nyan`：什么都不应答，这是对的——没有调用点需要任何值。
      nyan
    }
  }
}
```

* **裸 `nyan`** 是 `void` handler 的 `return`。只有在没有任何到达的 `scratch` 点需要值时才被接受；
  如果有，就会报 “this 'nyan' leaves no value, but the 'scratch' sites reaching this handler take
  `int`”。
* **裸 `yield`** 放弃这次调用，什么也不产生。它在语句形式的 `try` 里被接受（那种 `try` worth
  nothing），在结构本身有值的地方被拒绝——另一条路径会产生一个值，而这条路径不会。

在没人读取的地方仍然可以照旧写值（`void` handler 里的 `nyan 0` 就是返回 `0` 的普通 `return`），
所以这次放宽不会让任何已经能编译的程序失效。

#### 用 `try ... scratch` 捕获抛出的异常

上面的 `try` 写法都是**声明**一个 handler。另一个可以问的问题是调用**失败**时该怎么办，写法的
方式是在调用后面写一个已有 handler 的名字，而不是写一个块：

```neko
class Neko() {
  fun risky(d: int): int {
    return 100 / d
  }

  fun guarded(): int {
    // `risky(0)` 抛异常，所以由 handler 应答；调用自己的返回值不会被用到。
    v: int = try risky(0) scratch 1, 2
    return v
  } emit [int, int]

  fun invoker() {
    // 抛出异常，于是进入 handler，它应答 `1 + 2`。
    answer: int = try guarded() { a, b -> nyan a + b }
    println(answer)   // 3
  }
}
```

如果调用正常返回，这个结构保留调用自己的值：

```neko
class Neko() {
  fun risky(d: int): int {
    return 100 / d
  }

  fun guarded(): int {
    // `risky(2)` 返回 50，所以这个结构的值就是调用自己的值——`50`。
    v: int = try risky(2) scratch 9
    return v
  } emit [int]

  fun invoker() {
    answer: int = try guarded() { a -> nyan a * 2 }
    println(answer)   // 50，不是 18——什么都没抛出
  }
}
```

这两种写法是二选一，不是叠加。`try call(...) { a, b -> ... }` 说的是**这次调用 scratch 时做什么**；
`try call(...) scratch v1, v2` 说的是**这次调用抛异常时做什么**。所以：

| 写法 | 捕获 | 这个结构的值 |
| --- | --- | --- |
| `try call(...) { a -> ... }` | 它声明的 handler 所抛出的 `yield` | 调用的返回值 |
| `try call(...) scratch v1, v2` | 调用抛出的 `java.lang.Exception` | 正常返回时是调用的返回值，抛出时是 handler `nyan` 的答案 |

四条规则让第二种形式有明确的含义：

* **`scratch` 才是命名目的地的东西。** 它是一个普通的 `scratch`，所以所在函数必须声明 `emit`
  子句，且取值必须匹配——那个子句正是"外面哪个 handler 接收这次失败"的说明。在没有 `emit` 子句的
  函数里写 `try ... scratch` 会被拒绝，理由和裸 `scratch` 一样。
* **异常对象不可读。** handler 只接收写在 `scratch` 后面的值，别的什么都不接收，所以
  `scratch 1, 2` 送出的就是作者写的那两个值。异常只是触发器。
* **两条路径的类型必须一致。** 调用的返回值和 handler `nyan` 的答案是同一个问题的两个答案，所以
  它们必须是同一个类型。这也意味着 `void` 调用不能当作值使用——返回值那条路径上没有东西——而语句
  形式 `try call() scratch 1` 则完全可以用 `void` 调用。
* **只捕获 `Exception`，不捕获 `Error`。** handler 是普通的程序逻辑；让它吞掉 `OutOfMemoryError`
  或 `StackOverflowError` 会把致命失败变成一个错误答案，所以这些仍然向上传播。

正在离开的 `yield` **不是**失败，会原样穿过——`catch` 会不加处理地重新抛出它，所以用这个形式包住
一个调用，永远不会把发往另一个 `try` 的 effect 改道：

```neko
class Neko() {
  fun inner(): int {
    return scratch 21
  } emit [int]

  fun outer(): int {
    // `inner` 抛出的 `yield` 穿过这个形式到达 `invoker` 的 handler，所以结果由 `nyan a + 1` 应答，
    // 而不是这里写的 `scratch 99`。
    w: int = try inner() scratch 99
    return w
  } emit [int]

  fun invoker() {
    answer: int = try outer() { a -> nyan a + 1 }
    println(answer)   // 22
  }
}
```

### 检验程序的假设

```neko
count: int = 3
nya count > 0                              // 继续执行
nya count == 4                             // AssertionError: count == 4

nya count == 5 {                           // 失败被处理，程序继续
  println("count was not 5")
}

nya close() {                              // 不需要 `bool`：块回应的是抛出
  println("close() failed")
}

v: int = nya count == 3 {                  // 值一个结果：每种结局一个块
  yield 0                                  // 条件为 false，或抛出了
} else {
  yield 1                                  // 条件跑出了 true 的答案
}
```

`nya <条件>` 把程序所相信的东西写下来，并说明失败意味着什么。裸写时，条件是一个普通的 `bool`
表达式——一个比较、一个 `bool` 变量、一次调用——所以 `nya` 只是在你已经写下的东西上再加一句
「而且这必须成立」。

三种形式的差别在于失败值什么，而不在于测试怎么编译：

- **`nya 条件`**——条件为 false 时抛出 `java.lang.AssertionError`。**消息就是条件自身的源码
  文本**，所以失败信息读作 `count == 4`，而不是一句需要重新写一遍的说明，报告也不会和检查漂移。
- **`nya 条件 { ... }`**——块被执行而不是抛出，并且**控制权在它之后继续**。这就是这门语言用来
  代替 `try`/`catch` 的东西：能处理的失败就在发生的地方处理掉，程序照常继续。该块同时回应**两种**
  失败：条件算出 `false`，以及条件求值期间抛出的 `AssertionError`——可能是它自己裸写的 `nya`，
  也可能是它调用的某个函数里的。所以 `nya method() { ... }` 是一次其失败检查在调用点被处理的调用，
  而当块说明了失败意味着什么时，条件**不必是 `bool`**：`void` 没有可测的 false，这正是抛出成为
  唯一入口的原因。
- **`nya 条件 { ... } else { ... }`**——这个结构值一个结果。条件跑出 true 的答案时由 `else` 块
  给出该结果，条件为 false 或抛出时由失败块给出；两者都必须 yield，而且必须 yield 同一个类型。
  在值位置上只写一个失败块会被拒绝，因为没有东西说明条件成立时这个表达式值什么。

其余一切都可以从条件推出：

- **检查永远是开着的。** 和 Java 的 `assert` 不同，没有需要记住的 `-ea` 标志；一个静默什么都不做的
  `nya` 比没有 `nya` 更糟。
- **裸检查像守卫一样收窄。** 只有条件成立的那种读法才会到达下一条语句，所以 `nya h != null` 为它
  之后的一切证明 `h` 在那里——和 `if (h == null) { return }` 完全一样，所以之后 `h.value` 不需要
  `?.`。**带**失败块的检查在之后不收窄：控制权两条路都会到那里，所以到那时什么都没被证明。
- **失败块只相信两条来路都能证明的东西。** 值形式的 `else` 块只在条件跑出了答案时才被进入，所以
  它可以采用条件自身的读法——`nya h != null { ... } else { h.value }` 在 `else` 里不需要 `?.`。
  失败块也会被抛出进入，所以只有当条件不会抛出时它才得到那些事实：在
  `nya h == null || ready() { ... }` 里，从 `ready()` 抛出的异常到达时 `h` 仍是 null，所以那里的
  `h.value` 会被拒绝、需要自己再测一次。纯比较、名字和字面量不会抛出，所以普通的
  `nya h == null { ... }` 依然按你预期的方式收窄。

`nya` 刻意不是函数调用：它降低成的东西不是一次调用，而是一个要么抛出、要么执行一个块的分支——
有块时再加一个捕获条件可能抛出的 `AssertionError` 的 handler，只覆盖条件本身。

裸形式编译得和 Java 自己的 `assert` 完全一样：分支**跳过**抛出，让抛出跟在分支后面。

```
cond; ifne passed          // 一个成立检查的全部开销
throw new AssertionError("cond")
passed:
```

带块的两种形式无法保持这个形状——`else` 体、失败块和 handler 都必须汇聚到之后的同一个点上——
所以它们分支**到**失败处再跳过它。两种形状都由读生成指令的测试钉住，因为一条落在下一条指令上的
跳转，跑起来和不跳完全一样；只有字节码能区分它们。
