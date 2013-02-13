// Workaround for console.log problem.
if (!window.console) window.console = {};
if (!window.console.log) window.console.log = function () { };

// Azurite Library
var azurite = { };

try {
    // Being run in an IDE
	azurite.initialize = __AZURITE__initialize;
	azurite.selectiveUndo = __AZURITE__selectiveUndo;
} catch (e) {
    // Being run in a web browser.
    var alertFn;
    if (console.log) {
        console.log("AZURITE: Running Azurite in a non-editor environment.");
        alertFn = function () {
            console.log.apply(console, arguments);
        };
    } else {
        alertFn = alert;
    }
	
	azurite.initialize = function () {
		// Do nothing for now.
		alertFn('azurite.initialize() call');
	};
	
	azurite.selectiveUndo = function (arrayOfIds) {
		alertFn('azurite.selectiveUndo() call');
		alertFn(arrayOfIds);
	};
}