(function () {
  'use strict';
  registerPage({
    id: 'notebox',
    title: '笔记 · 待办',
    desc: 'NoteBox：笔记与待办（与云面板同域 /panel/notebox）',
    async mount(root) {
      const src = (CM.base || '/panel').replace(/\/$/, '') + '/notebox/';
      root.innerHTML = '<div class="card" style="padding:0;overflow:hidden"><iframe title="NoteBox" src="' + src + '" style="display:block;width:100%;min-height:calc(100vh - 140px);border:0;background:var(--bg)"></iframe></div>';
    },
  });
})();