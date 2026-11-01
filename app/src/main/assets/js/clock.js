/*
 * Offline Flip Clock - clock logic (ES5 only, Android 4.0 WebView compatible).
 *
 * - Reads local date/time from the device clock (new Date()). No network.
 * - One single setTimeout, aligned to the next minute boundary. No per-second timer,
 *   no requestAnimationFrame, no animation.
 * - DOM is touched only when a displayed value really changes.
 * - Public API used by MainActivity:  ClockApp.pause()  /  ClockApp.resume()
 * - Triple tap (3 taps within 900 ms) calls AndroidClock.closeApp().
 */
(function (window, document) {
    'use strict';

    var DESIGN_W = 1280;
    var DESIGN_H = 720;
    var TAP_WINDOW_MS = 900;
    var BOUNDARY_MARGIN_MS = 40; // fire just after the minute flips

    var MONTHS = ['JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE',
                  'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER'];

    var stageEl = document.getElementById('stage');
    var cornerEl = document.getElementById('corner');
    var hoursEl = document.getElementById('hours');
    var minutesEl = document.getElementById('minutes');
    var dateEl = document.getElementById('date');

    var timerId = null;
    var running = false;
    var shown = { h: '', m: '', d: '' };

    /* ------------------------------------------------------------ helpers */

    function pad2(n) {
        return n < 10 ? '0' + n : String(n);
    }

    function setText(el, value) {
        if (el.firstChild && el.firstChild.nodeType === 3) {
            el.firstChild.nodeValue = value;
        } else {
            el.innerHTML = '';
            el.appendChild(document.createTextNode(value));
        }
    }

    /* ------------------------------------------------------------ rendering */

    function render() {
        var now = new Date();
        var h = pad2(now.getHours());
        var m = pad2(now.getMinutes());
        var d = pad2(now.getDate()) + ' ' + MONTHS[now.getMonth()];

        if (h !== shown.h) { setText(hoursEl, h); shown.h = h; }
        if (m !== shown.m) { setText(minutesEl, m); shown.m = m; }
        if (d !== shown.d) { setText(dateEl, d); shown.d = d; } // handles midnight rollover
    }

    /* ------------------------------------------------------------ single minute-aligned timer */

    function clearTimer() {
        if (timerId !== null) {
            window.clearTimeout(timerId);
            timerId = null;
        }
    }

    function scheduleNextMinute() {
        clearTimer();
        var now = new Date();
        var msIntoMinute = now.getSeconds() * 1000 + now.getMilliseconds();
        var delay = 60000 - msIntoMinute + BOUNDARY_MARGIN_MS;
        if (delay < BOUNDARY_MARGIN_MS) { delay = BOUNDARY_MARGIN_MS; }
        timerId = window.setTimeout(onMinute, delay);
    }

    function onMinute() {
        timerId = null;
        if (!running) { return; }
        render();               // if the timer fired early, nothing changes in the DOM
        scheduleNextMinute();   // and the next delay is recomputed from the real clock
    }

    function pause() {
        running = false;
        clearTimer();
    }

    function resume() {
        running = true;
        render();               // immediate resync with the device clock
        scheduleNextMinute();
    }

    /* ------------------------------------------------------------ proportional scaling */

    function layout() {
        var w = window.innerWidth || document.documentElement.clientWidth || DESIGN_W;
        var h = window.innerHeight || document.documentElement.clientHeight || DESIGN_H;
        var scale = Math.min(w / DESIGN_W, h / DESIGN_H);
        if (!(scale > 0)) { scale = 1; }

        var stageW = DESIGN_W * scale;
        var stageH = DESIGN_H * scale;

        stageEl.style.fontSize = (100 * scale) + 'px';
        cornerEl.style.fontSize = (100 * scale) + 'px';
        stageEl.style.left = Math.round((w - stageW) / 2) + 'px';
        stageEl.style.top = Math.round((h - stageH) / 2) + 'px';
    }

    /* ------------------------------------------------------------ triple-tap exit */

    var taps = [];
    var closing = false;

    function now() {
        return new Date().getTime();
    }

    function closeApp() {
        if (closing) { return; }
        closing = true;
        pause();
        try {
            if (window.AndroidClock && window.AndroidClock.closeApp) {
                window.AndroidClock.closeApp();
                return;
            }
        } catch (e) { /* ignore */ }
        closing = false; // bridge unavailable (e.g. desktop preview)
    }

    function registerTap() {
        var t = now();
        taps.push(t);
        while (taps.length > 3) { taps.shift(); }
        if (taps.length === 3 && (taps[2] - taps[0]) <= TAP_WINDOW_MS) {
            taps = [];
            closeApp();
        }
    }

    function onTouchStart(e) {
        // Count only single-finger touches; pinch / multi-finger contacts are ignored.
        if (e.touches && e.touches.length > 1) {
            taps = [];
            return;
        }
        if (e.preventDefault) { e.preventDefault(); } // no zoom, no emulated mouse events
        registerTap();
    }

    function onMouseDown(e) {
        if (e.button !== undefined && e.button !== 0) { return; }
        registerTap();
    }

    function preventDefault(e) {
        if (e.preventDefault) { e.preventDefault(); }
    }

    /* ------------------------------------------------------------ start-up */

    if ('ontouchstart' in window) {
        document.addEventListener('touchstart', onTouchStart, false);
        document.addEventListener('touchmove', preventDefault, false);
    } else {
        document.addEventListener('mousedown', onMouseDown, false);
    }
    document.addEventListener('contextmenu', preventDefault, false);

    window.addEventListener('resize', layout, false);
    window.addEventListener('orientationchange', layout, false);

    window.ClockApp = {
        pause: pause,
        resume: resume
    };

    layout();
    resume();
    // Some Android 4.x WebViews report the final viewport size only after first layout.
    window.setTimeout(layout, 300);

}(window, document));
