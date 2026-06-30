(function (window, document) {

    var layout = document.getElementById('layout'),
        menu = document.getElementById('menu'),
        menuLink = document.getElementById('menuLink');

    function toggleClass(element, className) {
        var classes = element.className.split(/\s+/),
            length = classes.length,
            i = 0;

        for (; i < length; i++) {
            if (classes[i] === className) {
                classes.splice(i, 1);
                break;
            }
        }
        // The className is not found
        if (length === classes.length) {
            classes.push(className);
        }

        element.className = classes.join(' ');
    }

    menuLink.onclick = function (e) {
        var active = 'active';

        e.preventDefault();
        toggleClass(layout, active);
        toggleClass(menu, active);
        toggleClass(menuLink, active);
    };

}(this, this.document));

/*
 * Persist the menu's scroll position across navigations triggered by clicking a
 * nav link, so the menu does not jump when you click an item — the link you
 * clicked stays exactly where it was. (Pages cross-listed in two sections keep
 * the copy you actually clicked in view; both copies stay highlighted.) Only on
 * a *fresh* load — typed URL, refresh, or an external link, with no stored click
 * — is the active item scrolled into view, to orient the reader.
 */
var NAV_SCROLL_KEY = "mockserver.nav.scrollTop";
var NAV_FROM_CLICK_KEY = "mockserver.nav.fromClick";
// Decide restore-vs-fresh ONCE per load (consuming the click flag once), but
// still act on every invocation: this handler runs on both DOMContentLoaded and
// onload so the onload pass can re-assert/correct after layout settles.
var navMode = null;     // "restore" | "fresh"
var navSavedTop = 0;

(function () {
    var menu = document.getElementById("menu");
    if (!menu) {
        return;
    }
    menu.addEventListener("click", function (e) {
        var link = e.target.closest ? e.target.closest("a[href]") : null;
        // Only record same-tab navigations (ignore new-tab/external links).
        if (link && !link.target) {
            try {
                window.sessionStorage.setItem(NAV_SCROLL_KEY, String(menu.scrollTop));
                window.sessionStorage.setItem(NAV_FROM_CLICK_KEY, "1");
            } catch (err) { /* storage unavailable — ignore */ }
        }
    });
}());

var scrollActiveMenuItemIntoView = function() {
    var menu = document.getElementById("menu");
    if (!menu) {
        return;
    }
    if (navMode === null) {
        // Decide once, consuming the click flag.
        var fromClick = false, savedTop = null;
        try {
            fromClick = window.sessionStorage.getItem(NAV_FROM_CLICK_KEY) === "1";
            savedTop = window.sessionStorage.getItem(NAV_SCROLL_KEY);
            window.sessionStorage.removeItem(NAV_FROM_CLICK_KEY);
        } catch (err) { /* ignore */ }
        if (fromClick && savedTop !== null) {
            navMode = "restore";
            navSavedTop = parseInt(savedTop, 10) || 0;
        } else {
            navMode = "fresh";
        }
    }

    if (navMode === "restore") {
        // Navigated via the menu — keep it exactly where it was; re-assert the
        // saved position (no scroll-into-view) so the item you clicked stays put.
        menu.scrollTop = navSavedTop;
        return;
    }

    // Fresh load — orient the reader by revealing the active item, preferring
    // its real collapsible-section copy over the pinned "Popular" duplicate.
    var activeMenuItem = document.querySelector("#menu .nav-collapsible li.active")
        || document.querySelector("#menu li.active");
    if (activeMenuItem && !isInViewport(activeMenuItem)) {
        activeMenuItem.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'auto'});
    }
};

var isInViewport = function (elem) {
    var bounding = elem.getBoundingClientRect();
    return (
        bounding.top >= 0 &&
        bounding.left >= 0 &&
        bounding.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&
        bounding.right <= (window.innerWidth || document.documentElement.clientWidth)
    );
};

// Use addEventListener (not `window.onload =`) so this does not collide with
// accordion.js's own `window.onload` handler — both must run. The load pass
// re-asserts/corrects the scroll after layout settles; navMode makes the two
// invocations (DOMContentLoaded + load) idempotent.
document.addEventListener("DOMContentLoaded", scrollActiveMenuItemIntoView);
window.addEventListener("load", scrollActiveMenuItemIntoView);
