package outwatch

import scala.scalajs.js
import org.scalajs.dom.{Element, document}
import outwatch.dom._
import outwatch.dom.dsl._
import org.scalajs.dom.raw.CSSStyleDeclaration
import snabbdom._
import outwatch.dom.helpers.SnabbdomOps.toSnabbdom

class RepairDomSpec extends JSDomAsyncSpec {

  private def attributeMap(elem: Element): Map[String, String] = {
    val attributes = elem.attributes
    var attrs = Map.empty[String, String]
    for (i <- 0 until attributes.length) {
      attrs += (attributes(i).name -> attributes(i).value)
    }
    attrs
  }

  private def styleMap(elem: Element): Map[String, String] = {
    val style = elem.style
    var styles = Map.empty[String, String]
    for (i <- 0 until style.length) {
      val styleName = style.item(i)
      val value = style.getPropertyValue(styleName)
      styles += (styleName -> value)
    }
    styles
  }

  implicit class ElementWithStyle(val elem: Element) {
    def style: CSSStyleDeclaration = elem.asInstanceOf[js.Dynamic].style.asInstanceOf[CSSStyleDeclaration]
  }

  private def testCase(vNode: VNode, corruption: Element => Any) = {
    val proxy = toSnabbdom(vNode)

    val originalNode = document.createElement("div")
    val fragileNode = document.createElement("div")
    document.body.appendChild(originalNode)
    document.body.appendChild(fragileNode)
    patch(originalNode, proxy)
    patch(fragileNode, proxy)

    corruption(fragileNode)
    VNodeProxy.repairDom(proxy)

    fragileNode.innerHTML shouldBe originalNode.innerHTML
    styleMap(fragileNode) shouldBe styleMap(originalNode)
    attributeMap(fragileNode) shouldBe attributeMap(originalNode)
  }


  // nodes
  "RepairDom: Nodes" should "removed first node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.removeChild(elem.firstChild)
      }
    )
  }

  it should "removed middle node" in {
    testCase(
      vNode = div(div(), span(), code()),
      corruption = { elem =>
        elem.removeChild(elem.childNodes(1))
      }
    )
  }

  it should "removed last node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.removeChild(elem.lastChild)
      }
    )
  }

  it should "replaced first node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.firstChild)
      }
    )
  }

  it should "replaced middle node" in {
    testCase(
      vNode = div(div(), span(), code()),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.childNodes(1))
      }
    )
  }

  it should "replaced last node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.lastChild)
      }
    )
  }

  it should "prepended node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.insertBefore(document.createElement("a"), elem.firstChild)
      }
    )
  }

  it should "inserted node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.insertBefore(document.createElement("a"), elem.lastChild)
      }
    )
  }
  it should "appended node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.appendChild(document.createElement("a"))
      }
    )
  }

  // text nodes
  "RepairDom: Text nodes" should "removed first text node" in {
    testCase(
      vNode = div("dev", span()),
      corruption = { elem =>
        elem.removeChild(elem.firstChild)
      }
    )
  }

  it should "removed middle text node" in {
    testCase(
      vNode = div(div(), "spain", code()),
      corruption = { elem =>
        elem.removeChild(elem.childNodes(1))
      }
    )
  }

  it should "removed last text node" in {
    testCase(
      vNode = div(div(), "spain"),
      corruption = { elem =>
        elem.removeChild(elem.lastChild)
      }
    )
  }

  it should "replaced first node by text node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("aaaa"), elem.firstChild)
      }
    )
  }

  it should "replaced middle node by text node" in {
    testCase(
      vNode = div(div(), span(), code()),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("aaaa"), elem.childNodes(1))
      }
    )
  }

  it should "replaced last node by text node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("aaaa"), elem.lastChild)
      }
    )
  }

  it should "replaced first text node by node" in {
    testCase(
      vNode = div("boom", span()),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.firstChild)
      }
    )
  }

  it should "replaced middle text node by node" in {
    testCase(
      vNode = div(div(), "wurm", code()),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.childNodes(1))
      }
    )
  }

  it should "replaced last text node by node" in {
    testCase(
      vNode = div(div(), "reigen"),
      corruption = { elem =>
        elem.replaceChild(document.createElement("a"), elem.lastChild)
      }
    )
  }

  it should "replaced first text node by text node" in {
    testCase(
      vNode = div("boom", span()),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("a"), elem.firstChild)
      }
    )
  }

  it should "replaced middle text node by text node" in {
    testCase(
      vNode = div(div(), "wurm", code()),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("a"), elem.childNodes(1))
      }
    )
  }

  it should "replaced last text node by text node" in {
    testCase(
      vNode = div(div(), "reigen"),
      corruption = { elem =>
        elem.replaceChild(document.createTextNode("a"), elem.lastChild)
      }
    )
  }

  it should "prepended text node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.insertBefore(document.createTextNode("a"), elem.firstChild)
      }
    )
  }

  it should "inserted text node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.insertBefore(document.createTextNode("a"), elem.lastChild)
      }
    )
  }
  it should "appended text node" in {
    testCase(
      vNode = div(div(), span()),
      corruption = { elem =>
        elem.appendChild(document.createTextNode("a"))
      }
    )
  }

  // attributes
  "RepairDom: Attributes" should "removed attribute" in {
    testCase(
      vNode = div(id := "ich"),
      corruption = { elem =>
        elem.removeAttribute("id")
      }
    )
  }

  it should "replaced attribute" in {
    testCase(
      vNode = div(id := "ich"),
      corruption = { elem =>
        elem.setAttribute("id", "wir")
      }
    )
  }

  it should "added attribute" in {
    testCase(
      vNode = div(id := "ich"),
      corruption = { elem =>
        elem.setAttribute("color", "tomato")
      }
    )
  }

  // styles
  "RepairDom: Styles" should "removed style" in {
    testCase(
      vNode = div(margin := "3px"),
      corruption = { elem =>
        elem.style.removeProperty("margin")
      }
    )
  }

  it should "replaced style" in {
    testCase(
      vNode = div(margin := "3px"),
      corruption = { elem =>
        elem.style.setProperty("margin", "1px")
      }
    )
  }

  it should "added style" in {
    testCase(
      vNode = div(padding := "7px"),
      corruption = { elem =>
        elem.style.setProperty("margin", "2px")
      }
    )
  }

  // transitive children
  "RepairDom: transitive children" should "removed node with transitive children" in {
    testCase(
      vNode = div(div(div(), span())),
      corruption = { elem =>
        elem.removeChild(elem.firstChild)
      }
    )
  }

  it should "added node with transitive children" in {
    testCase(
      vNode = div(),
      corruption = { elem =>
        val child = document.createElement("a")
        child.appendChild(document.createElement("div"))
        elem.appendChild(child)
      }
    )
  }
}
