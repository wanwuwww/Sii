// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * 「选取广告元素」：页面注入脚本高亮手指下的元素，点击后回传候选选择器（含匹配数），
 * 由浏览器弹确认框决定试隐或写入 `域名##选择器` 规则。
 */
class AdPickBridge {

    /** 一个候选选择器：selector 为 CSS 选择器，count 为当前页面匹配数。 */
    data class Candidate(val selector: String, val count: Int, val label: String)

    /** 开始选取时的域名（由浏览器在进入选取模式时设置）。 */
    var host: String = ""

    /** 只在用户主动进入标记广告模式时允许提交。 */
    @Volatile
    var active: Boolean = false

    /** 候选回传（主线程）。 */
    var onCandidates: ((List<Candidate>) -> Unit)? = null

    @JavascriptInterface
    fun pick(payload: String) {
        if (!active) return
        active = false
        val candidates = runCatching {
            val array = org.json.JSONArray(payload)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val selector = obj.optString("selector").trim()
                if (selector.isEmpty()) null
                else Candidate(selector, obj.optInt("count", 0), obj.optString("label"))
            }
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return
        Handler(Looper.getMainLooper()).post { onCandidates?.invoke(candidates) }
    }

    companion object {
        /**
         * 选取脚本：触摸跟随高亮，点击时收集「选中元素 + 最近的 fixed/高 z-index 祖先 + 覆盖视口的兄弟遮罩」，
         * 选择器优先 id / 稳定 class / data-* 属性，跳过哈希类名；回传每个选择器的匹配数量。
         * 用原始字符串书写，避免 JS 正则与引号被 Kotlin 转义层改写。
         */
        val PICK_JS: String = """
(function(){
if(window.__siiAdPick)return;
window.__siiAdPick=true;
var cur=null;
var HASH=/^[a-f0-9]{6,}$/i;
function stableClass(el){
  try{
    var list=el.classList;
    if(!list)return '';
    for(var i=0;i<list.length;i++){
      var c=list[i];
      if(!c||c.length>32||HASH.test(c))continue;
      return c;
    }
  }catch(e){}
  return '';
}
function selectorFor(el){
  try{
    if(!el||!el.tagName)return '';
    var tag=el.tagName.toLowerCase();
    if(el.id&&!HASH.test(el.id))return '#'+el.id;
    var cls=stableClass(el);
    if(cls)return tag+'.'+cls;
    var attrs=el.attributes||[];
    for(var i=0;i<attrs.length;i++){
      var n=attrs[i].name;
      var v=(attrs[i].value||'').trim();
      if(n.indexOf('data-')!==0&&n!=='aria-label'&&n!=='role')continue;
      if(!v||v.length>40||HASH.test(v))continue;
      if(v.indexOf("'")>=0||v.indexOf('"')>=0)continue;
      return tag+"["+n+"='"+v+"']";
    }
    var path=[],e=el,depth=0;
    while(e&&e.tagName&&e.tagName.toLowerCase()!=='body'&&depth<4){
      var s=e.tagName.toLowerCase();
      var c2=stableClass(e);
      if(c2)s+='.'+c2;
      var p=e.parentElement;
      if(p)s+=':nth-child('+(Array.prototype.indexOf.call(p.children,e)+1)+')';
      path.unshift(s);
      e=p;
      depth++;
    }
    return path.join('>');
  }catch(e){return '';}
}
function covers(el){
  try{
    var r=el.getBoundingClientRect();
    var vw=window.innerWidth*window.innerHeight;
    return vw>0&&(r.width*r.height)/vw>0.35;
  }catch(e){return false;}
}
function isOverlay(el){
  try{
    var cs=window.getComputedStyle(el);
    if(cs.position!=='fixed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;
    var z=parseInt(cs.zIndex||'0',10)||0;
    return z>=500||covers(el);
  }catch(e){return false;}
}
function add(list,el,label){
  try{
    if(!el||!el.tagName)return;
    var sel=selectorFor(el);
    if(!sel)return;
    for(var i=0;i<list.length;i++)if(list[i].selector===sel)return;
    var count=0;
    try{count=document.querySelectorAll(sel).length;}catch(e){return;}
    list.push({selector:sel,count:count,label:label});
  }catch(e){}
}
function hl(el){
  if(cur)cur.style.outline='';
  cur=el;
  if(cur)cur.style.outline='2px solid #f44336';
}
function onMove(ev){
  try{
    var t=ev.touches[0];
    hl(document.elementFromPoint(t.clientX,t.clientY));
  }catch(e){}
}
function onClick(ev){
  try{
    ev.preventDefault();
    ev.stopPropagation();
    var target=ev.target;
    var list=[];
    add(list,target,'选中的元素');
    var e=target?target.parentElement:null;
    var up=0;
    while(e&&up<5){
      if(isOverlay(e)){add(list,e,'弹窗容器');break;}
      e=e.parentElement;
      up++;
    }
    var p=target?target.parentElement:null;
    if(p){
      for(var i=0;i<p.children.length&&i<20;i++){
        var sib=p.children[i];
        if(sib===target)continue;
        if(covers(sib))add(list,sib,'遮罩层');
      }
    }
    if(window.SiiAdPick&&list.length)window.SiiAdPick.pick(JSON.stringify(list));
    if(cur)cur.style.outline='';
  }catch(e){}
  document.removeEventListener('touchmove',onMove,true);
  document.removeEventListener('click',onClick,true);
  window.__siiAdPick=false;
}
document.addEventListener('touchmove',onMove,{capture:true,passive:true});
document.addEventListener('click',onClick,true);
})();
        """.trimIndent()
    }
}
