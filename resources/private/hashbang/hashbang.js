;(function() {
  "use strict";
  window.lupapisteApp = new LUPAPISTE.App({startPage: "hashbang",
                                           allowAnonymous: true,
                                           logoPath: window.location.pathname + window.location.hash,
                                           showUserMenu: false});
  $(function() {
    lupapisteApp.domReady();
    var model = {continueUrl: lupapisteApp.getHashbangUrl(),
                 registerUrl: "/app/" + loc.getCurrentLanguage() + "/welcome#!/register"};
    $("#hashbang").applyBindings(model);
  });

})();
