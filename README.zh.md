# Neko 语言

Neko 是一门用来做编译器实验的玩具 JVM 语言：它有类、接口、`emit`/`scratch`/`nyan` 错误控制机制，
并且直接编译成 Java 字节码。

[English](README.md)

## 项目结构

构建是一个 Gradle 多模块工程。编译器和 IDE 插件是彼此独立的模块，因此用其中一个不会拖入另一个：
插件依赖编译器，而编译器从不反过来依赖插件。

| 路径            | 模块           | 说明                                                     |
|-----------------|----------------|----------------------------------------------------------|
| `neko/`         | `:neko`        | 编译器：词法、语法、AST、分析器、字节码生成              |
| `plugin/idea/`  | `:plugin:idea` | NekoJ，基于 `:neko` 的 IntelliJ IDEA 插件                |
| `stdlib/`       | —              | Neko 标准库，由编译器自己编译                            |
| `samples/`      | —              | 示例程序                                                 |
| `neko-compile/` | —              | 编译输出，不纳入版本控制                                 |

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
./gradlew :neko:test                     # 编译器：476 个测试
./gradlew :plugin:idea:test              # 插件：51 个测试，跑在真实 IDE 实例里

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

### 补全提供什么

补全列出的是分析器在这一位置真正能解析到的符号：局部变量、参数、字段、函数、类和包。这也是
「名字只有存在之后才会出现」的含义——补全和诊断读的是同一份解析结果，不会各说一套。

### 自动 import

补全可以插入一条 `import`：选中一个位于尚未导入的包里的类时，向导会补上 `import <包>`；
如果该名字在多个包里都有，则补上钉住某个具体类的 `import <包>.<类>`。

### 无法解析的名字

无法解析的名字按编译器的措辞报告，因此编辑器里的红线和命令行上的错误是同一条消息。

### 高亮是怎么做的

高亮的 token 就是词法器的 token，原样从编译器传递过来，所以不存在第二套着色规则。

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

1. 程序自己的声明，按简单名，不论在哪个包——编译单元是整个程序，所以这一步永远最先命中，
   import 只可能*增加*解析一个名字的途径；
2. `java.lang`，所以 `Exception` 和 `String` 不需要任何子句；
3. 已导入的包，按源码顺序。

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

JDK 类的成员通过反射解析，并按反射出的 owner 和 descriptor 派发；没有一个重载匹配的调用会被拒绝，
并告诉你该类到底声明了什么。类名在接收者被当作值之前就先匹配，因为那里根本没有值——而且既然 Neko
没有静态导入语法，`Cls.member` 就是访问静态成员唯一可用的写法。

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

### 值类（`meow`）

`meow class` 让编译器根据字段生成 `equals` 和 `hashCode`，所以一个「值」类型的比较语义
不必手写也不会写错。

### 枚举（`miao`）

```neko
miao class Color {
    RED,
    GREEN,
    BLUE
}
```

常量是真正的 `java.lang.Enum` 实例，所以 `name()`、`ordinal()`、`values()` 和 `valueOf()`
都来自 JDK 并且可用。

常量后面可以带值，值在类体里声明：

```neko
miao class Code(val status: int) {
    OK(200),
    GONE(410)
}
```

### 锁（`purr`、`purred`、`purrcls`、`purredcls`）

`purr` 块把监视器锁的获取与释放变成语法结构，而不是一对必须自己配平的调用的约定：

```neko
purr (lock) {
    // 持有 lock 时执行
}
```

`purred` 给方法加锁，`purrcls` / `purredcls` 加的是类级别（静态）的锁。

### 可空类型

类型后面加 `?` 表示它可以是「缺席」。`T?` 是 `null` 唯一能赋值的类型：

```neko
name: string? = null
name = "kusa"
```

`T` 不能接受 `null`，编译器会直接拒绝——这正是可空类型存在的意义：类型本身告诉你是否必须检查。

`?.` 是使用可空值的方式：`h?.value` 在 `h` 为 `null` 时给出 `null`，`h?.f(...)` 只在 `h` 存在时
才调用 `f`。因为结果是可空的，安全调用可以串起来：`a?.b?.c`。

`?(...)` 对*函数值*做同样的事：`f?(1, 2)` 只在 `f` 存在时调用它。

### 智能转换

在 `if` 里检查过非空之后，同一个值在分支内就不再需要 `?.`：

```neko
h: Holder? = Holder(4)

if (h != null) {
    // 这里 h 是 Holder，不需要 `?.`
} else {
    // 这里 h 仍然需要 `?.`
}
```

离开分支后它又变回 `T?`。对 `int?` 来说这比便利更多：`count != null` 之后 `count == 1`
可以直接工作。

### 内置类型

八个基本类型是 `byte`、`short`、`char`、`int`、`long`、`float`、`double` 和 `bool`
（也可以写 `boolean`）。它们的 Java 引用名 `Byte`、`Short`、`Character`、`Integer`、`Long`、
`Float`、`Double`、`Boolean` 指的是**同一个**类型，而不是需要互相转换的两套。

另外还有 `string`（也可写 `String`）、`Object` 和 `Throwable`。

### 数值规则

类型之间没有隐式转换，`int + string` 是错误。`long` 不是「更宽的 `int`」，而是一个恰好拼写相近的
不同类型。字面量是唯一的例外。

### 字面量

```neko
i: int = 1
l: long = 1L
d: double = 1.5
s: string = "neko"
b: bool = true
```

### 局部变量

```neko
x: int = 1
x = 2
```

本地变量默认可变；写 `val` 则不可变。

### 循环（`for`、`for ... in`）

```neko
for (i: int = 0; i < 10; i++) {
}

for (item in items) {
}
```

`for ... in` 走的是 `java.lang.Iterable` / `java.util.Iterator`，所以任何 JDK 集合都能直接遍历，
任何实现了该接口的 Neko 类也一样。

### 入口点

```neko
class Main {
    static {
        fun main() {
            println("hello")
        }
    }
}
```

### 错误控制

Neko 用 `emit` 子句、`scratch` 和 `try`/`nyan` 来表达「可能提前带着一个值离开」。与异常不同，
能抛出什么写在签名上，所以它不会在调用链里凭空出现。

### 检验程序的假设

`nya` 表达「这里应该成立」，不成立就停下。
