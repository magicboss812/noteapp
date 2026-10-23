%%% 01 plain paragraph
Hello world
%%% 02 two paragraphs
First paragraph.

Second paragraph.
%%% 03 soft wrapped paragraph
line one
line two
line three
%%% 04 hard break with backslash
first\
second
%%% 05 empty paragraphs between blocks
a



b
%%% 06 h1
# Title
%%% 07 h2 and h3
## Section
### Subsection
%%% 08 heading without space is text
#hashtag
%%% 09 setext heading
Title
=====
%%% 10 bold italic
**bold** and *italic* and ***both***
%%% 11 underscores
__bold__ and _italic_
%%% 12 strikethrough
~~gone~~ and kept
%%% 13 inline code
use `foo()` here
%%% 14 highlight
this is ==important== text
%%% 15 inline math
Euler: $e^{i\pi}+1=0$ is nice
%%% 16 dollar amounts stay literal
costs $5 and $6 in total
%%% 17 escaped dollar
price \$5 and \$6
%%% 18 math with underscores and stars
$a_b * c_d$ stays math
%%% 19 doc link shortcut
see [[Meeting notes]] later
%%% 20 doc link resolved
see [Meeting notes](folio://doc/3f2a9c10-7b1d-4e55-9a57-0d7c1c9a1111)
%%% 21 web link
open [site](https://example.com/a?b=c) now
%%% 22 image degrades to text
![alt text](pic.png)
%%% 23 bullets
- one
- two
- three
%%% 24 star and plus bullets
* one
+ two
%%% 25 numbered list
1. one
2. two
3. three
%%% 26 numbered with paren
1) one
2) two
%%% 27 nested bullets
- parent
  - child
    - grandchild
%%% 28 task list
- [ ] open
- [x] done
%%% 29 list items with blank lines between
- a

- b

- c
%%% 30 list item with continuation line
- first line
  continued here
- next
%%% 31 empty bullet
-
- after
%%% 32 quote
> quoted text
> second line
%%% 33 callout note
> [!NOTE]
> Remember this
%%% 34 callout warning inline
> [!WARNING] careful
%%% 35 quote with list
> - a
> - b
%%% 36 fenced code
```kotlin
fun main() {
    println("hi")
}
```
%%% 37 fenced code with blank lines
```
one

two
```
%%% 38 tilde fence
~~~
tilde
~~~
%%% 39 unclosed fence
```
never closed
%%% 40 indented code
    indented code
    second
%%% 41 math block
$$
\int_0^1 x^2 dx
$$
%%% 42 math block single line
$$ a^2 + b^2 = c^2 $$
%%% 43 math block with blank line inside
$$
a

b
$$
%%% 44 horizontal rules
---

***

___
%%% 45 table
| a | b |
|---|---|
| 1 | 2 |
| 3 | 4 |
%%% 46 table alignment
| left | center | right |
|:-----|:------:|------:|
| x    | y      | z     |
%%% 47 table without outer pipes
a | b
--|--
1 | 2
%%% 48 table with inline markup
| **bold** | `code` |
|---|---|
| $x^2$ | ==hl== |
%%% 49 html block
<div>raw html</div>
%%% 50 inline html
a <b>bold</b> tag
%%% 51 link reference definition
[ref]: https://example.com
%%% 52 mixed document
# Notes

Intro paragraph with **bold**.

- [ ] task
- [x] done

> [!TIP]
> Use the pen.

```
code
```

| a | b |
|---|---|
| 1 | 2 |

$$
x = 1
$$

---

The end.
%%% 53 paragraph then list without blank line
text
- item
%%% 54 paragraph then heading without blank line
text
# Head
%%% 55 ordered item cannot interrupt paragraph
text
2. not a list
%%% 56 unicode
Grüße, 你好, 🎉 and ∑ symbols
%%% 57 trailing spaces
spaced   
next
%%% 58 tabs
-	tab item
	continued
%%% 59 long line
Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam.
%%% 60 only blank-looking text
   
