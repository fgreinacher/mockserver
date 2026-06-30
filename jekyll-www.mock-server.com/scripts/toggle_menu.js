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

var scrollActiveMenuItemIntoView = function() {
    // Prefer the active link in its real (collapsible) section over the pinned
    // "Popular" duplicate, so the nav scrolls to reveal the page's sibling /
    // related links rather than stranding the user on the isolated Popular copy.
    var activeMenuItem = document.querySelector("#menu .nav-collapsible li.active")
        || document.querySelector("#menu li.active");
    if (activeMenuItem && !isInViewport(activeMenuItem)) {
        // Centre it so the sibling/related links above and below stay visible.
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

window.onload = scrollActiveMenuItemIntoView;

document.addEventListener("DOMContentLoaded", scrollActiveMenuItemIntoView);
