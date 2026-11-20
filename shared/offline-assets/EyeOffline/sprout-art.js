/* Original vector artwork for the interactive garden. */
window.sproutArt=(()=>{
  const svg=(box,content)=>'<svg viewBox="'+box+'" '+(box==='0 0 380 280'?'preserveAspectRatio="none"':'')+' aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">'+content+'</svg>';
  const items=[
    '<path d="m24 39 13-19 5 4-14 19" fill="#c8946a"/><path d="m34 21 5-8q2-3 5-1l5 4q2 2 0 5l-5 6z" fill="#edc89e"/><path d="m41 20 2-3 3 2-2 3z" fill="#5a4334"/><path d="m23 32 12 9-8 10q-6 7-15 6-1-10 5-18z" fill="#d9e5cd"/><path d="m26 36-10 16 14-13" fill="#9bb4a7"/>',
    '<path d="M32 6C28 17 12 29 12 41a20 20 0 0 0 40 0C52 30 40 18 32 6Z" fill="#78d7e8"/><path d="M32 6C32 22 22 31 22 42c0 9 7 15 15 17-13 4-26-6-25-19 0-12 16-26 20-34Z" fill="#459ab9"/><path d="M39 31q6 7 6 13" fill="none" stroke="#dbffff" stroke-width="4" stroke-linecap="round"/>',
    '<path d="m17 12 32 3-4 7 5 26q1 5-5 6L15 51q-5-1-4-6l8-25z" fill="#eeb16e"/><path d="m17 12 32 3-1 6-29-3z" fill="#ffe1a4"/><path d="m15 26 31 3 1 16-34-3z" fill="#fbdb9e"/><path d="M27 40q-5-12 9-12 0 10-9 12Z" fill="#598961"/><path d="m26 42 7-11" stroke="#42644d" stroke-width="1.8"/>',
    '<g transform="rotate(18 32 32)"><path d="M24 18q-4-10-8-9m23 10q4-9 9-8M17 31l-7-3m7 13-8 3m38-12 7-3m-7 14 7 3" fill="none" stroke="#e8cda9" stroke-width="3.5" stroke-linecap="round"/><ellipse cx="32" cy="20" rx="11" ry="10" fill="#343c39"/><ellipse cx="32" cy="37" rx="19" ry="22" fill="#f28262"/><path d="M32 17v41" stroke="#493d39" stroke-width="2.3"/><g fill="#3b403b"><circle cx="23" cy="29" r="4"/><circle cx="42" cy="33" r="4"/><circle cx="22" cy="44" r="3.7"/><circle cx="39" cy="48" r="3.7"/></g><path d="M17 32q1-6 6-9" fill="none" stroke="#ffc3a0" stroke-width="2.6" stroke-linecap="round"/></g>'
  ];
  const item=i=>svg('0 0 64 64',items[i]);
  function coin(){
    // A two-leaf mint mark matches the leaves of the plant that produced the coin.
    return svg('0 0 64 64',
      '<ellipse cx="33" cy="35" rx="23" ry="24" fill="#986124"/>'+
      '<ellipse cx="31" cy="31" rx="23" ry="24" fill="#f8c76e" stroke="#ffe9a8" stroke-width="1.6"/>'+
      '<ellipse cx="31" cy="31" rx="18.6" ry="19.4" fill="#f3bd60" stroke="#be842e" stroke-width="1.5"/>'+
      '<path d="M16 30q0-10 10-15m9 31q6-1 9-7" fill="none" stroke="#ffe7a5" stroke-width="2" opacity=".9" stroke-linecap="round"/>'+
      '<g fill="#a1692b"><path d="M30 36q-12 1-13-9 12-2 13 9Zm1-4q1-13 14-14-1 12-14 14Z"/>'+
      '<path d="M29 43q3-13 10-19" fill="none" stroke="#a1692b" stroke-width="2.6" stroke-linecap="round"/></g>'+
      '<path d="M15 15q4-5 9-7" fill="none" stroke="#fff4cc" stroke-width="2.1" stroke-linecap="round"/>');
  }
  function plant(level,bug=false,pulse=false){
    const stage=Math.max(0,Math.min(4,Math.floor(level)||0));
    const insect=[[110,126],[119,100],[132,108],[132,108],[145,88]][stage];
    const pest=bug ? '<g transform="translate('+insect[0]+' '+insect[1]+')" fill="#e7c38a"><ellipse rx="4.6" ry="3.1"/><circle cx="4" cy="-1" r="2"/></g>' : '';
    return svg('31 0 164 194',window.LilyArt.potted(stage,pulse)+pest);
  }
  return {item,plant,coin};
})();
