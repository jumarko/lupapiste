$(function() {
  "use strict";

  $("footer")
    .append("<br>")
    .append("<input type='checkbox' id='debug-todo' checked='checked'>N&auml;yt&auml; toteuttamattomat")
    .append("<input type='checkbox' id='debug-hidden'>K&auml;&auml;nn&auml; piilotetut")
    .append("<input type='checkbox' id='debug-events'>N&auml;yt&auml; eventit")
    .append("<a id='debug-apply-minimal' href='#' style='margin-left: 10px'>Apply minimal</a>")
    .append("<span id='debug-apply-done' style='display: none'> DONE</span>");

  $(".todo").addClass("todo-off");
  $("#debug-todo").click(function() { $(".todo").toggleClass("todo-off"); });
  $("#debug-hidden").click(function() { $(".page").toggleClass("visible"); });
  $("#debug-events").click(function() { hub.send("toggle-show-events"); });
  $("#debug-apply-minimal").click(function(e) {
    ajax.get("http://localhost:8000/api/query/apply-fixture")
      .param("name", "minimal")
      .success(function() { if (window["repository"]) repository.reloadAllApplications(); $("#debug-apply-done").show(); })
      .call();
    return false;
  });

  hub.subscribe("page-change", function() { $("#debug-apply-done").hide(); });
  
  // Helper function to execute xpath queries. Useful for testing xpath declarations in robot files.
  window.xpath = function(p) { return document.evaluate(p, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; };

});
