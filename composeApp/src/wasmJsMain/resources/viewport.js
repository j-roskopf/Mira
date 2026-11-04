(function () {
    var viewport = window.visualViewport;
    if (!viewport) {
        return;
    }

    var frame = 0;

    function sync() {
        cancelAnimationFrame(frame);
        frame = requestAnimationFrame(function () {
            var app = document.getElementById("composeApp");
            if (!app || viewport.scale > 1.01) {
                return;
            }

            var height = Math.round(viewport.height) + "px";
            var top = Math.round(viewport.offsetTop) + "px";
            if (app.style.height === height && app.style.top === top) {
                return;
            }

            app.style.height = height;
            app.style.top = top;
            window.dispatchEvent(new Event("resize"));
        });
    }

    viewport.addEventListener("resize", sync);
    viewport.addEventListener("scroll", sync);
    sync();
})();
