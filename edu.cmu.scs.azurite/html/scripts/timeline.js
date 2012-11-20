// Workaround for console.log problem.
if (!window.console) window.console = {};
if (!window.console.log) window.console.log = function () { };

// variables to remember the last window size
var lastWindowWidth = null, lastWindowHeight = null;

// left, right, top, bottom
var menu_panel_height = 75;
var menu_margins = {left: 5, top: 5};
var chart_margins = {left: 5, right:5, top: 15, bottom: 50};


// Constants
var INSERTION = 0;
var DELETION = 1;
var REPLACEMENT = 2;

var LOCAL_MODE = true;

var files = [];
var blocks_to_draw = [];
var selected = [];
var xmlDoc = null;

// last file opened
var current_file = null;

// maximum timestamp so far
var max_timestamp = -1;

// starting timestamp (offset)
var startTimestamp;

// context menu
var div_context;
var isRightClicked;

/**
*	An object that keeps track of insertion, deletion and replacement for each file.
*/
function File(path, fileName) {
	this.path = path;
	this.fileName = fileName;
	this.event = new Array();
}


// Type 0 : insertion, 1 : deletion, 2: replacement
function Event(id, timestamp, timestamp2, type) {
	this.id = id;
	this.timestamp = timestamp;
	this.timestamp2 = timestamp2;
	this.type = type;
	this.color;
	
	if(type == INSERTION)
		this.color = "green";
	else if(type == DELETION)
		this.color = "red";
	else if(type == REPLACEMENT)
		this.color = "blue";
}

/**
 * Block object to draw
 */
function Block(id, width, height, x, y, color, timestamp, timestamp2) {
	this.id = id;
	this.width = width;
	this.height = height;
	this.x = x;
	this.y = y;
	this.color = color;
	this.timestamp = timestamp;
	this.timestamp2 = timestamp2;
}

// Loading local xml file
function loadFile() {

	if(LOCAL_MODE == true) {
		var xmlhttp;
		
		if (window.XMLHttpRequest) { 
			// code for IE7+, Firefox, Chrome, Opera, Safari
			xmlhttp = new XMLHttpRequest();
		} else { 
			// code for IE6, IE5
			xmlhttp = new ActiveXObject("Microsoft.XMLHTTP");
		}

		xmlhttp.open("GET","Log2012-09-24-10-41-36-725.xml",false);
		xmlhttp.send();
		xmlDoc = xmlhttp.responseXML; 
	} else {
		 // disable this block temporarily.
		return;

	
		var log = readLog();

		if(log == "") {
			return;
		}
		
		if (window.DOMParser) {

			var parser = new DOMParser();
			xmlDoc = parser.parseFromString(log,"text/xml");
			

			
		} else { // Internet Explorer
			xmlDoc = new ActiveXObject("Microsoft.XMLDOM");
			xmlDoc.async = false;
			xmlDoc.loadXML(text).responseXML;
		}	
	}
}


function parseXml() {
	if(xmlDoc == null) {
		return;
	}

	startTimestamp = parseInt(xmlDoc.childNodes[0].getAttribute("startTimestamp"))
	
	var command_list = xmlDoc.childNodes[0].childNodes;

	for(var i in command_list) {
		var command = command_list[i];
		
		if(command.tagName === "Command") {
			// Add a new file to file list if file this is a file never opened before
			if(command.getAttribute("_type") === "FileOpenCommand") {
				for(var j in command.childNodes) {
					if(command.childNodes[j].tagName == "filePath") {
						var path = command.childNodes[j].textContent;
						var fileName = path.match(/[^\\/]+$/)[0];
						
						// check if this file was opened before
						checkFileList(path, fileName);
						break;
					}
					
					
				}
			}
		} else if (command.tagName === "DocumentChange" ) {	
			var type;
			var id = command.getAttribute("__id");
			
			if(command.getAttribute("_type") === "Insert") {
				type = INSERTION;
			} else if(command.getAttribute("_type") === "Delete") {
				type = DELETION;
			} else if(command.getAttribute("_type") === "Replace") {
				type = REPLACEMENT;
			}
			
			if(type === INSERTION || type === DELETION || type === REPLACEMENT) {
				var timestamp = parseInt(command.getAttribute("timestamp"));
				var timestamp2 = command.getAttribute("timestamp2");
				
				if(timestamp2 == null) {
					timestamp2 = null;
				} else {
					timestamp2 = parseInt(timestamp2);
				}
				
				current_file.event.push(new Event(id, timestamp, timestamp2, type));
			
				// update max_timestamp if necessary
				if(timestamp2 == null && timestamp > max_timestamp) {
					max_timestamp = timestamp;
				} else if(timestamp2 != null && timestamp2 > max_timestamp) {
					max_timestamp = timestamp2;
				}
			}
			
		}
	}
}

/**
 * 	Returns true is fileName exists in files. Otherwise false
 */
function checkFileList(path, fileName) {
	for(var k in files) {
		if(files[k].fileName === fileName) {
			current_file = files[k];
			return;
		}
	}
	
	var file = new File(path, fileName);
	files.push(file);
	current_file = file;
}

// Current index of zoom level
var bar_zoom_index = 0, file_zoom_index = 0;

// Page index to show based on currrent zoom level
// eg. If zoom level is 0, and if page index is 0, show from 0 to 49. If index is 2, show from 100 to 149
var bar_cur_index = 0, file_cur_index = 0;
var bar_max_page_index, file_max_page_index;

// 5 min, 20 min, 1 hour, 4 hour
var bar_zoom_levels = [300000,1200000,3600000,14400000000];

// height of each file title
var file_zoom_levels = [30,40,50,60];

// Min and max timestamp to show at current zoom level
var min_to_show, max_to_show;

// An array of timestamps from min_timestamp to max_timestamp
var enumerated;

var width_ratio = [0.2, 0.8];

// Width of bars
var x_bar, x_rule;
var num_x_ticks;

var svg = d3.select('#main_panel')
	.append('svg')
	.attr('class', 'svg')
	.attr('id', 'svg');

var svg_element = document.getElementById('svg');

	
var sub_file = svg.append('g')
	.attr('class', 'sub_file')
	
var sub_bar = svg.append('g')
	.attr('id', 'sub_bar');
	
var blocks = sub_bar.append('g')
	.attr('class', 'blocks');
	
var fileLines = sub_bar.append('g')
	.attr('class', 'fileLines');

var scrollbar_x = sub_bar.append('g')
	.attr('class', 'scrollbar_x');
	
var rules = sub_bar.append('g')
	.attr('class', 'rules');

var brush = d3.svg.brush()
	.on("brushstart", brushstart)
	.on("brushend", brushend);



//var temp = new Event(100, 0);

function add_file(path) {
	var fileName = path.match(/[^\\/]+$/)[0];
	
	for(var index in files) {
		if(files[index].path == path) {
			current_file = files[index];
			return;
		}
	}
	
	var newFile = new File(path, fileName);
	files.push(newFile);
	current_file = newFile;
	redraw();
}

function set_start_timestamp(timestamp) {
  startTimestamp = parseInt(timestamp);
  redraw();
}


/**
 * Add an event to the end of the file
 */
function add_block(id, timestamp1, timestamp2, type) {
//	alert(typeof parameter);
//	alert(parameter);
	
	var newEvent = new Event(parseInt(id), parseInt(timestamp1), parseInt(timestamp2), parseInt(type));
	
	var file_index = -1;
	
	for(var i = 0; i < files.length; i++) {
		if(files[i].path == current_file.path) {
			file_index = i;
		}
	}
	
	if(file_index == -1)
		return;
	
	/*
	// If not drawn here, it will be drawn in redraw()
	for(var i = min_to_show; i <= max_to_show; i++) {
		var event = current_file.event[i];
		//debugger;
		if(event == null) {
			sub_bar.append("rect")
				.attr("width", bar_size)
				.attr("height", 80)
				.attr("x", x_bar(i))
				.attr("y", file_index * 100 + 10)
				.style("fill-opacity",0.6)
				.style("fill", newEvent.color);
			break;
		}
	}
	current_file.event.push(newEvent);
	*/
	
	current_file.event.push(newEvent);
  
	// update max_timestamp if necessary
	if(timestamp2 == null && timestamp > max_timestamp) {
		max_timestamp = timestamp;
	} else if(timestamp2 != null && timestamp2 > max_timestamp) {
		max_timestamp = timestamp2;
	}

	redraw();
}

function redraw() {
	var svg_width = lastWindowWidth;
	var svg_height = lastWindowHeight - menu_panel_height;
	var chart_width = svg_width - chart_margins.left - chart_margins.right;
	var chart_height = svg_height - chart_margins.top - chart_margins.bottom;
	
	sub_file.selectAll("text").remove();
	fileLines.selectAll('line').remove();
	rules.selectAll('text').remove();
	sub_bar.selectAll('rect').remove();
	
	selected = [];
	sub_bar.selectAll('polygon').remove();
	
	
	
	svg.attr("width", svg_width)
		.attr("height", svg_height)
		
	svg_element.style.left = 0 + 'px';
	svg_element.style.top = menu_panel_height + 'px';
	

	bar_max_page_index = Math.ceil(max_timestamp / bar_zoom_levels[bar_zoom_index])-1;
	
	if(bar_cur_index > bar_max_page_index)
		bar_cur_index = bar_max_page_index;
	
	min_to_show = bar_cur_index * bar_zoom_levels[bar_zoom_index];
	max_to_show = min_to_show + bar_zoom_levels[bar_zoom_index];

	// using the current svg height, determine the number of files to draw
	var num_file_to_show = Math.floor(chart_height / file_zoom_levels[file_zoom_index]);
	file_max_page_index = Math.ceil(files.length / num_file_to_show) - 1;
	
	// calculate the number of files to draw
	var files_to_draw = [];
	var offset = num_file_to_show * file_cur_index;
	
	for(var i = 0; i < num_file_to_show && files[offset+i] != null; i++)
		files_to_draw.push(files[offset+i]);
	
	draw_menu();
	draw_chart(files_to_draw, chart_width, chart_height);
	draw_rule(chart_width, chart_height);
	draw_scrollbar(chart_width, chart_height);
	
	//debugger;
	
	console.log("CHART HEIGHT " + chart_height);
	

}

function draw_menu() {
	var button_width = 50, button_height = 30;

	// draw panel
	var div_menu_panel = document.getElementById('menu_panel');

	div_menu_panel.style.left = 0 + 'px';
	div_menu_panel.style.top = 0 + 'px';
	div_menu_panel.style.width = lastWindowWidth + 'px';
	div_menu_panel.style.height = menu_panel_height + 'px';

	// draw timeline bar control
	var bar_zoom_level = document.getElementById('bar_zoom_level');
	var bar_page_index = document.getElementById('bar_page_index');
	var button_before = document.getElementById('button_before');
	var button_after = document.getElementById('button_after');
	var bar_zoom_in = document.getElementById('bar_zoom_in');
	var bar_zoom_out = document.getElementById('bar_zoom_out');
	
	bar_zoom_level.style.left = menu_margins.left + 'px';
	bar_zoom_level.style.top = menu_margins.top + button_height + 'px';
	bar_zoom_level.innerHTML = "Zoom Index : " + bar_zoom_index + "/" + (bar_zoom_levels.length-1);
	
	bar_page_index.style.left = menu_margins.left + 'px';
	bar_page_index.style.top = menu_margins.top + button_height + 20 + 'px';	
	bar_page_index.innerHTML = "Page Index : " + bar_cur_index + "/" + bar_max_page_index;
	
	button_before.style.width = button_width + 'px';
	button_before.style.height = button_height + 'px';
	button_before.style.left = menu_margins.left + 'px';
	button_before.style.top = menu_margins.top + 'px';

	button_after.style.width = button_width + 'px';
	button_after.style.height = button_height + 'px';
	button_after.style.left = menu_margins.left + button_width + 'px';
	button_after.style.top = menu_margins.top + 'px';
	
	bar_zoom_in.style.width = button_width + 'px';
	bar_zoom_in.style.height = button_height + 'px';
	bar_zoom_in.style.left = menu_margins.left + (button_width*2) + 'px';
	bar_zoom_in.style.top = menu_margins.top + 'px';
	
	bar_zoom_out.style.width = button_width + 'px';
	bar_zoom_out.style.height = button_height + 'px';
	bar_zoom_out.style.left = menu_margins.left + (button_width*3) + 'px';
	bar_zoom_out.style.top = menu_margins.top + 'px';
	
	// draw file list control
	var file_zoom_level = document.getElementById('file_zoom_level');
	var file_page_index = document.getElementById('file_page_index');
	var button_up = document.getElementById('button_up');
	var button_down = document.getElementById('button_down');
	var file_zoom_in = document.getElementById('file_zoom_in');
	var file_zoom_out = document.getElementById('file_zoom_out');
	
	file_zoom_level.style.left = menu_margins.left + (button_width*5) + 'px';
	file_zoom_level.style.top = menu_margins.top + button_height + 'px';
	file_zoom_level.innerHTML = "Zoom Index : " + file_zoom_index + "/" + (file_zoom_levels.length-1);
	
	file_page_index.style.left = menu_margins.left + (button_width*5) + 'px';
	file_page_index.style.top = menu_margins.top + button_height + 20 + 'px';	
	file_page_index.innerHTML = "Page Index : " + file_cur_index + "/" + file_max_page_index;
	
	button_up.style.width = button_width + 'px';
	button_up.style.height = button_height + 'px';
	button_up.style.left = menu_margins.left + (button_width*5) +'px';
	button_up.style.top = menu_margins.top + 'px';

	button_down.style.width = button_width + 'px';
	button_down.style.height = button_height + 'px';
	button_down.style.left = menu_margins.left + (button_width*6) + 'px';
	button_down.style.top = menu_margins.top + 'px';
	
	file_zoom_in.style.width = button_width + 'px';
	file_zoom_in.style.height = button_height + 'px';
	file_zoom_in.style.left = menu_margins.left + (button_width*7) + 'px';
	file_zoom_in.style.top = menu_margins.top + 'px';
	
	file_zoom_out.style.width = button_width + 'px';
	file_zoom_out.style.height = button_height + 'px';
	file_zoom_out.style.left = menu_margins.left + (button_width*8) + 'px';
	file_zoom_out.style.top = menu_margins.top + 'px';
	
}

function draw_chart(files_to_draw, chart_width, chart_height) {
	// draw file list
	sub_file.attr('transform', 'translate(' + chart_margins.left + ',' + chart_margins.top + ')');
	
	sub_file.selectAll('.fileTitles')
		.data(files_to_draw)
		.enter().append('text')
		.text(function(d) { return d.fileName; })
		.attr('x', 0)
		.attr('y', function(d,i) { return (file_zoom_levels[file_zoom_index] * i) +  file_zoom_levels[file_zoom_index]/2} )
		.attr('dy', '0.5ex')
		.attr('text-anchor', 'start')
		.attr('class', 'fileTitle')
		.attr('fill', 'white');
	
	// draw bars
	sub_bar.attr('transform', 'translate(' + (chart_margins.left +(chart_width * 0.15)) + ',' + chart_margins.top + ')');
	var bar_width = chart_width * 0.85;
	
	// draw file lines
	for(var i = 0; i < files_to_draw.length+1; i++) {
		fileLines.append('line')
			.attr('x1', 0)
			.attr('y1', file_zoom_levels[file_zoom_index] * i)
			.attr('x2', bar_width)
			.attr('y2', file_zoom_levels[file_zoom_index] * i)
			.attr('stroke', 'lightgray' )
			.style('stroke-width', 1);
	}
	
	x_rule = d3.scale.linear()
		.domain([0, 4])
		.range([min_to_show, max_to_show]);
	
	x_bar = d3.time.scale()
		.domain([min_to_show, max_to_show])
		.range([0, bar_width]);
		
	y = d3.scale.linear()
		.domain([0, chart_height])
		.range([0, chart_height]);
	
	
	
	// select blocks to draw
	blocks_to_draw = [];
	var min_width = bar_width * 0.005;
	
	for(var i = 0; i < files_to_draw.length; i++) {
		var file = files_to_draw[i];
		var events = file.event;
		var length = events.length;
		var width;
		
		for(var j = 0; j < length; j++) {
			var timestamp = events[j].timestamp;
			var timestamp2 = events[j].timestamp2
	
			if(timestamp >= min_to_show && timestamp <= max_to_show) {
				
				if(timestamp2 == null) {
					if(x_bar(timestamp) + min_width > bar_width) {
						width = x_bar(max_to_show) - x_bar(timestamp);
					} else {
						width = min_width;
					}
				} else {
				
					if(timestamp2 > max_to_show)
						timestamp2 = max_to_show;
				
					width = x_bar(timestamp2) - x_bar(timestamp);

					if(width < min_width)
						width = min_width;
					
				}
				
				blocks_to_draw.push(
					new Block(
						events[j].id,
						width, 
						file_zoom_levels[file_zoom_index], 
						x_bar(timestamp), 
						(file_zoom_levels[file_zoom_index] * i),
						events[j].color, 
						timestamp, 
						timestamp2)
				);
					
			} else if(timestamp2 != null && timestamp2 >= min_to_show && timestamp2 <= max_to_show) {
				
				width = x_bar(timestamp2) - x_bar(min_to_show);
				
				if(width < min_width) 
					width = min_width;
				
				blocks_to_draw.push(
					new Block(
						events[j].id,
						width, 
						file_zoom_levels[file_zoom_index], 
						x_bar(timestamp), 
						(file_zoom_levels[file_zoom_index] * i),
						events[j].color, 
						timestamp, 
						timestamp2)
				);
				
			
			} else {
				break;
			}
		}
	}
	
	
	blocks.selectAll("rect")
		.data(blocks_to_draw).enter().append("rect")
		.attr("width", function(d) { return d.width; })
		.attr("height", function(d) { return d.height; })
		.attr("x", function(d) { return d.x; })
		.attr("y", function(d) { return d.y; })
		.style("fill", function(d) { return d.color; });
	
	
	blocks.call(brush.x(x_bar).y(y));
}


function draw_rule(chart_width, chart_height) {
	rules.selectAll(".rule")
		.data(x_rule.ticks(5))
		.enter().append("text")
		.attr("x", function(d,i) { return x_bar(x_rule(i)); })
		.attr("y", chart_height + 15)
		.attr("text-anchor", function(d,i) {
			if(i == 0)
				return "start";
			else if(i == 4)
				return "end";
			else 
				return "middle";
		})
		.attr('fill', 'white')
		.text(function(d,i) { return (new Time(startTimestamp + x_rule(i))).toString(); });
}

function draw_scrollbar(chart_width, chart_height) {
	
	
	
	$('#scrollbar_x_wrapper').css({
		"position": 'absolute',
		"left": (chart_width * 0.15 + 10) + "px",
		"top": chart_height + menu_panel_height + 40 + "px",
		'width': (chart_width * 0.85 - 15) + 'px'
	})
	
	scroll_x = $('#scrollbar_x').slider({
		value: bar_cur_index,
		min: 0,
		max: bar_max_page_index,
		step: 1,
		orientation: 'horizontal',

		slide: function(event, ui) {
			console.log('slide');
			//debugger;
			bar_cur_index = ui.value;
			redraw();
		},
		
		change: function(event, ui) {
			console.log('change');
			
			//bar_cur_index = ui.value;
			//redraw();
		}
	})
	
	
	var handleSize = scroll_x.width() / (bar_max_page_index + 1);
	scroll_x.find('.ui-slider-handle').css({
		width: handleSize,
		'margin-left': -handleSize / 2
	});
	/*
	scrollbar_x.append('rect')
		.attr('x', button_width)
		.attr('y', chart_height)
		.attr('width', width - 2* button_width)
		.attr('height', height)
		.attr('fill', 'grey');
		
	scrollbar_x.append('rect')
		.attr('x', button_width)
		.attr('y', chart_height)
		.attr('width', 100)
		.attr('height', height)
		.attr('fill', '#2F4F4F');
		
	scrollbar_x.append("svg:image")
      .attr("xlink:href", "./img/left.png")
	  .attr('x', 0)
	  .attr('y', chart_height)
      .attr("width", button_width)
      .attr("height", height)
	  .on('click', function() { console.log("left")});
	  
	scrollbar_x.append("svg:image")
      .attr("xlink:href", "./img/right.png")
	  .attr('x', width - button_width)
	  .attr('y', chart_height)
      .attr("width", button_width)
      .attr("height", height)
	  .on('click', function() { console.log("right")});*/
}


function test() {
	console.log("HHHHHHHHHHHHHHHHHHHHHHH");
}
/*
 * When the page loads, load a log file
 */
window.onload = function () {
	console.log("ON LOAD");
	console.log(window.innerWidth);
	console.log(window.innerHeight);

	
	loadFile();
	parseXml();
	initContextMenu();
	
	if(lastWindowWidth != window.innerWidth || lastWindowHeight != window.innerHeight) {
		lastWindowWidth = window.innerWidth;
		lastWindowHeight = window.innerHeight;
		
		file_cur_index = 0;
		bar_cur_index = 0;
		
		redraw();
	}
	
}

window.onresize = function(event) {
    console.log("ON RESIZE");
	console.log(window.innerWidth);
	console.log(window.innerHeight);
	
	// if window size are different, redraw everything
	if(lastWindowWidth != window.innerWidth || lastWindowHeight != window.innerHeight) {
		lastWindowWidth = window.innerWidth;
		lastWindowHeight = window.innerHeight;
		
		file_cur_index = 0;
		bar_cur_index = 0;
		
		redraw();
	}
	
	
}

/******************************************************************
 MOUSE EVENT FUNCTIONS
 ******************************************************************/
 
function initContextMenu() {
	div_context = document.getElementById('context_menu');
	
	div_context.onmouseover = function() { mouseOverContext = true; };
	div_context.onmouseout = function(e) {
		e = event.toElement || event.relatedTarget;
		
		while(e && e.parentNode && e.parentNode != window) {
			if(e.parentNode == this || e == this) {
				return;
			}
			e = e.parentNode;
		}
		
		hideContextMenu();
	};
}
 
function showContextMenu(event) {
	var offset_x = 0, offset_y = 0;
	
	if(event.clientX + parseInt(div_context.style.width) > lastWindowWidth) {
		offset_x = event.clientX + parseInt(div_context.style.width) - lastWindowWidth
	}
	
	if(event.clientY + parseInt(div_context.style.height) > lastWindowHeight) {
		offset_y = event.clientY + parseInt(div_context.style.height) - lastWindowHeight;
	}
	
	div_context.style.left = event.clientX - offset_x -10 + 'px';
	div_context.style.top = event.clientY -offset_y -10 + 'px';
	div_context.style.display = 'block';
}

function hideContextMenu() {
	div_context.style.display = 'none';
}
 
function brushstart() {
	var event = d3.event.sourceEvent;
	//debugger;
	if ("which" in event) { // Gecko (Firefox), WebKit (Safari/Chrome) & Opera
		isRightClicked = event.which == 3; 
	} else if ("button" in event) { // IE, Opera 
		isRightClicked = event.button == 2; 
	}	
	
	
	if(isRightClicked) {
		console.log("Right Clicked");
		d3.select('.extent').remove();
		showContextMenu(event);
	}
}
 
function brushend() {
	if(isRightClicked) {
		blocks.call(brush.clear());
		return;
	}
	
	selected = [];
	sub_bar.selectAll('polygon').remove();
	
	
	var extent = brush.extent();
	
	var upperLeft = extent[0];
	var lowerRight = extent[1];
	console.log(extent);
	
	var blockLength = blocks_to_draw.length;
	for(var i = 0; i < blockLength; i++) {
		if(trivial_reject_test(upperLeft, lowerRight, blocks_to_draw[i]) == 0) {
			selected.push(blocks_to_draw[i]);
		}
	}
	
	console.log(selected);
	blocks.call(brush.clear());
	
	// check overlapping blocks decide width and height of highlight boxes
	var itemsToHighlight = [];
	
	
	var selectedLength = selected.length;
	
	if(selectedLength > 0) {
		var prev = selected[0];
		var item = {startX: prev.x, startY: prev.y, endX: prev.x + prev.width, endY: prev.y + prev.height};
		
		for(var i = 1; i < selectedLength; i++) {
			if(item.startY == selected[i].y &&  Math.abs(item.endX - selected[i].x) <= 8) {
				item.endX = (item.endX > (selected[i].x + selected[i].width)) ? item.endX : (selected[i].x + selected[i].width);
			} else {
				itemsToHighlight.push(item);
				item = {startX: selected[i].x, startY: selected[i].y, endX: selected[i].x + selected[i].width, endY: selected[i].y + selected[i].height};
			}
			/*
			if(item.startY == selected[i].y &&  item.endX >= selected[i].x) {
				item.endX = (item.endX > (selected[i].x + selected[i].width)) ? item.endX : (selected[i].x + selected[i].width);
			} else {
				itemsToHighlight.push(item);
				item = {startX: selected[i].x, startY: selected[i].y, endX: selected[i].x + selected[i].width, endY: selected[i].y + selected[i].height};
			}*/
			
			prev = selected[i];
		}
		
		itemsToHighlight.push(item);
		
		console.log(itemsToHighlight);
		
		var highlight_width = 3;
		
		blocks.selectAll('polygon')
			.data(itemsToHighlight).enter().append('polygon')
			.attr("points", function(d) { return ((d.startX) + "," + (d.startY) + " \ " + 
			(d.endX) + "," + (d.startY) + " \ " +
			(d.endX) + "," + (d.endY) + " \ " +
			(d.startX) + "," + (d.endY)) })
			.style("stroke", "yellow")
			.style("stroke-width", highlight_width)
			.style("fill-opacity", 0);
			
	}
}
	
function trivial_reject_test(upperLeft, lowerRight, block) {
	var result0 = 0, result1= 0;
	var left = 1;
	var right = 2;
	var bottom = 4;
	var top = 8;
	
	
	if(x_bar(upperLeft[0]) < block.x) {
		result0 = result0 | left;
	} else if(x_bar(upperLeft[0]) > (block.x + block.width)) {
		result0 = result0 | right;
	}
	
	if(upperLeft[1] < block.y) {
		result0 = result0 | top;
	} else if(upperLeft[1] > (block.y + block.height)) {
		result0 = result0 | bottom;
	}
	
	if(x_bar(lowerRight[0]) < block.x) {
		result1 = result1 | left;
	} else if(x_bar(lowerRight[0]) > (block.x + block.width)) {
		result1 = result1 | right;
	}
	
	if(lowerRight[1] < block.y) {
		result1 = result1 | top;
	} else if(lowerRight[1] > (block.y + block.height)) {
		result1 = result1 | bottom;
	}
	
	return (result0 & result1);
}

	
/******************************************************************
 LISTENER FUNCTIONS
 ******************************************************************/
function bar_zoom_in() {
	if(bar_zoom_index != 0) {
		bar_zoom_index--;
		redraw();
	}
}

function bar_zoom_out() {
	if(bar_zoom_index != (bar_zoom_levels.length - 1)) {
		bar_zoom_index++;
		
		redraw();
	}
}

function show_before() {
	if(bar_cur_index > 0) {
		bar_cur_index--;
		redraw();
	}
}

function show_after() {
	if(bar_cur_index < bar_max_page_index) {
		bar_cur_index++;
		redraw();
	}
}

function file_zoom_in() {
	if(file_zoom_index != 0) {
		file_zoom_index--;
		redraw();
	}
}

function file_zoom_out() {
	if(file_zoom_index != (file_zoom_levels.length - 1)) {
		file_zoom_index++;
		redraw();
	}
}

function show_up() {
	if(file_cur_index > 0) {
		file_cur_index--;
		redraw();
	}
}

function show_down() {
	if(file_cur_index < file_max_page_index) {
		file_cur_index++;
		redraw();
	}
}

function undo() {
	if(selected.length > 0) {
		var result = [];
		
		for(var i = 0; i < selected.length; i++) {
			result.push(selected[i].id);
		}
	
		doUndo(result);
	
	}

}