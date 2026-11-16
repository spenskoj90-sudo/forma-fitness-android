package ru.forma.app

import kotlinx.serialization.json.Json

/** kotlinx.serialization validates syntax/types; this bounded pass also denies duplicate object keys. */
internal fun requireUniqueJsonFields(text:String) {
 var at=0
 fun whitespace(){while(at<text.length && text[at].isWhitespace())at++}
 fun expect(c:Char){whitespace();require(at<text.length && text[at++]==c)}
 fun string():String {
  whitespace();val begin=at;require(at<text.length && text[at++]=='"')
  while(at<text.length){
   when(text[at++]){
    '\\'->{require(at<text.length);at++}
    '"'->return Json.decodeFromString<String>(text.substring(begin,at))
   }
  }
  throw IllegalArgumentException("Unterminated manifest string")
 }
 fun value(depth:Int) {
  require(depth<=12);whitespace();require(at<text.length)
  when(text[at]){
   '{'->{
    at++;whitespace();val keys=mutableSetOf<String>()
    if(at<text.length && text[at]=='}'){at++;return}
    while(true){
     require(keys.add(string()));expect(':');value(depth+1);whitespace();require(at<text.length)
     if(text[at]=='}'){at++;break};expect(',')
    }
   }
   '['->{
    at++;whitespace()
    if(at<text.length && text[at]==']'){at++;return}
    while(true){value(depth+1);whitespace();require(at<text.length)
     if(text[at]==']'){at++;break};expect(',')}
   }
   '"'->string()
   else->{val begin=at;while(at<text.length && text[at] !in ",]}" && !text[at].isWhitespace())at++;require(at>begin)}
  }
 }
 value(0);whitespace();require(at==text.length)
}
