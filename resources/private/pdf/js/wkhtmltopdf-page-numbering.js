// Injects page numbering and number of pages into pdf generated by wkhtmltopdf.
// usage:
//  Insert tag(s) with id #page-number and/or #number-of-pages into header or footer
//   html-template and add this script in the end of html-template body to provide page
//   numbering on every page of pdf document.
(function() {
  "use strict";
  var pdfInfo = {};
  var x = document.location.search.substring(1).split("&");
  for (var i in x) {
    if (i) {
      var z = x[i].split("=",2);
      pdfInfo[z[0]] = decodeURIComponent(z[1]);
    }
  }

  var page = pdfInfo.page || 1;
  var pageCount = pdfInfo.topage || 1;

  document.getElementById("page-number").textContent = page;
  document.getElementById("number-of-pages").textContent = pageCount;
})();
