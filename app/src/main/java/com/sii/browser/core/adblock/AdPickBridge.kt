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
         * 选取脚本：按下即高亮，抬手时拦截默认点击（否则触摸阶段就跳去广告），
         * 收集「选中元素 + 链接容器 + 最近的 fixed/高 z-index 祖先 + 覆盖视口的兄弟遮罩」，
         * 选择器优先 id / 稳定 class / data-* 属性，跳过哈希类名；回传每个选择器的匹配数量。
         * 选取期间关闭 iframe/frame 的指针事件，让广告框的点击落在主文档上。
         * 用原始字符串书写，避免 JS 正则与引号被 Kotlin 转义层改写。
         */
        val PICK_JS: String = """
(function(){
if(window.__siiAdPick)return;
window.__siiAdPick=true;
var cur=null;
var startX=0,startY=0,startTarget=null,moved=false,finished=false;
var disabledFrames=[];
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
function disableFrames(){
  try{
    var frames=document.querySelectorAll('iframe,frame');
    for(var i=0;i<frames.length;i++){
      var f=frames[i];
      try{
        disabledFrames.push({el:f,old:f.style.pointerEvents});
        f.style.pointerEvents='none';
      }catch(e){}
    }
  }catch(e){}
}
function restoreFrames(){
  for(var i=0;i<disabledFrames.length;i++){
    try{disabledFrames[i].el.style.pointerEvents=disabledFrames[i].old;}catch(e){}
  }
  disabledFrames=[];
}
function finishAt(x,y,target){
  if(finished)return;
  finished=true;
  try{
    var t=target||document.elementFromPoint(x,y);
    var list=[];
    add(list,t,'选中的元素');
    var link=t&&t.closest?t.closest('a'):null;
    if(link)add(list,link,'链接容器');
    var e=t?t.parentElement:null;
    var up=0;
    while(e&&up<5){
      if(isOverlay(e)){add(list,e,'弹窗容器');break;}
      e=e.parentElement;
      up++;
    }
    var p=t?t.parentElement:null;
    if(p){
      for(var i=0;i<p.children.length&&i<20;i++){
        var sib=p.children[i];
        if(sib===t)continue;
        if(covers(sib))add(list,sib,'遮罩层');
      }
    }
    if(window.SiiAdPick&&list.length)window.SiiAdPick.pick(JSON.stringify(list));
  }catch(e){}
  cleanup();
}
function cleanup(){
  if(cur)cur.style.outline='';
  cur=null;
  restoreFrames();
  document.removeEventListener('touchstart',onTouchStart,true);
  document.removeEventListener('touchmove',onMove,true);
  document.removeEventListener('touchend',onTouchEnd,true);
  document.removeEventListener('touchcancel',onTouchCancel,true);
  document.removeEventListener('click',onClick,true);
  window.__siiAdPick=false;
  window.__siiAdPickCleanup=null;
}
function onTouchStart(ev){
  try{
    var t=ev.touches[0];
    startX=t.clientX;startY=t.clientY;
    startTarget=document.elementFromPoint(startX,startY);
    moved=false;
    hl(startTarget);
  }catch(e){}
}
function onMove(ev){
  try{
    var t=ev.touches[0];
    if(Math.abs(t.clientX-startX)>10||Math.abs(t.clientY-startY)>10)moved=true;
    hl(document.elementFromPoint(t.clientX,t.clientY));
  }catch(e){}
}
function onTouchEnd(ev){
  try{
    if(finished)return;
    if(moved){hl(null);return;}
    ev.preventDefault();
    ev.stopPropagation();
    finishAt(startX,startY,startTarget);
  }catch(e){}
}
function onTouchCancel(){
  try{hl(null);}catch(e){}
}
function onClick(ev){
  try{
    if(finished)return;
    ev.preventDefault();
    ev.stopPropagation();
    finishAt(ev.clientX||0,ev.clientY||0,ev.target);
  }catch(e){}
}
window.__siiAdPickCleanup=cleanup;
disableFrames();
document.addEventListener('touchstart',onTouchStart,{capture:true,passive:true});
document.addEventListener('touchmove',onMove,{capture:true,passive:true});
document.addEventListener('touchend',onTouchEnd,{capture:true,passive:false});
document.addEventListener('touchcancel',onTouchCancel,true);
document.addEventListener('click',onClick,true);
})();
        """.trimIndent()

        /** 退出选取：移除监听、恢复 iframe 指针事件，避免残留状态吞掉正常点击。 */
        val EXIT_JS: String = """
(function(){
  if(window.__siiAdPickCleanup){
    try{window.__siiAdPickCleanup();}catch(e){}
  }
  window.__siiAdPick=false;
  window.__siiAdPickCleanup=null;
})();
        """.trimIndent()
    }
}
