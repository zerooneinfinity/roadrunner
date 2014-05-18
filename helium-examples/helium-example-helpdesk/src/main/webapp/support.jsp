<%@ page contentType="text/html; charset=utf-8" language="java"%>
<%
    String hostName = request.getServerName() + ":"
		    + request.getServerPort();
%>
<!DOCTYPE html>
<!--[if lt IE 7]>      <html class="no-js lt-ie9 lt-ie8 lt-ie7"> <![endif]-->
<!--[if IE 7]>         <html class="no-js lt-ie9 lt-ie8"> <![endif]-->
<!--[if IE 8]>         <html class="no-js lt-ie9"> <![endif]-->
<!--[if gt IE 8]><!-->
<html class="no-js" ng-app="helpdesk">
<!--<![endif]-->
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
<title>Helpdesk</title>
<meta name="description" content="">
<meta name="viewport" content="width=device-width">

<link rel="stylesheet" href="client/css/bootstrap.min.css">
<style>
body {
	padding-top: 60px;
	padding-bottom: 40px;
}
</style>
<link rel="stylesheet" href="client/css/bootstrap-responsive.min.css">
<link rel="stylesheet" href="client/css/main.css">

<script src="client/js/vendor/modernizr-2.6.2-respond-1.1.0.min.js"></script>
<script src="client/js/jquery.min.js"></script>

<script src="angular.min.js"></script>
<script src="repo/helium.js"></script>
<script src="helium-webrtc.js"></script>
<script src="angularHelium.js"></script>
<script src="support.js"></script>
</head>
<body ng-controller="HelpdeskCtrl">
	<!--[if lt IE 7]>
            <p class="chromeframe">You are using an <strong>outdated</strong> browser. Please <a href="http://browsehappy.com/">upgrade your browser</a> or <a href="http://www.google.com/chromeframe/?redirect=true">activate Google Chrome Frame</a> to improve your experience.</p>
        <![endif]-->

	<!-- This code is taken from http://twitter.github.com/bootstrap/examples/hero.html -->

	<div class="navbar navbar-inverse navbar-fixed-top">
		<div class="navbar-inner">
			<div class="container">
				<a class="btn btn-navbar" data-toggle="collapse"
					data-target=".nav-collapse"> <span class="icon-bar"></span> <span
					class="icon-bar"></span> <span class="icon-bar"></span>
				</a> <a class="brand" href="#">Helpdesk</a>
				<div class="nav-collapse collapse">
					<ul class="nav">
						<li><a href="index.html">Home</a></li>
						<li class="active"><a href="#">Support Desk</a></li>
						<li class="dropdown"><a href="#" class="dropdown-toggle"
							data-toggle="dropdown">Account <b class="caret"></b></a>
							<ul class="dropdown-menu">
								<li><a href="j_spring_security_logout">Logout</a></li>
							</ul></li>
					</ul>
				</div>
				<!--/.nav-collapse -->
			</div>
		</div>
	</div>

	<div class="container">
		<div class="row">
			<div class="span8">
				<div id="processes">
					<span>{{sessions.length}} Open sessions</span>
					<table class="table table-striped">
						<thead>
							<tr>
								<th>Name</th>
								<th>Status</th>
								<th>Action</th>
							</tr>
						</thead>
						<tbody>
							<tr ng-repeat="session in sessions">
								<td>{{session.name}}</td>
								<td>{{session.status}}</td>
								<td>
									<div class="btn-toolbar">
										<div class="btn-group">
											<a class="btn" href="#" ng-click="support(session)">Support</a>
											<a class="btn" href="#" ng-click="remove(session)"><i
												class="icon-trash"></i></a>
										</div>
									</div>
								</td>
							</tr>
						</tbody>
					</table>
				</div>
				<form id="form" class="form-horizontal">
					<div class="control-group">
						<label class="control-label" for="inputName">Name</label>
						<div class="controls">{{name}}</div>
					</div>
					<div class="control-group">
						<label class="control-label" for="inputJob">Job</label>
						<div class="controls">{{job}}</div>
					</div>
					<div class="control-group">
						<label class="control-label" for="inputCountry">Country</label>
						<div class="controls">{{country}}</div>
					</div>
				</form>
			</div>
			<div class="span4">
				<div id="video">
					<div class="bs-docs bs-docs-remote">
						<video id="webrtc-remoteVideoElement" autoplay></video>
					</div>
					<div class="bs-docs bs-docs-local">
						<video id="webrtc-localVideoElement" autoplay muted></video>
					</div>
					<!--<button class="btn btn-primary" type="button"
						onclick="webRTC.connect();">Connect</button>
					<button class="btn btn-primary" type="button"
						onclick="webRTC.disconnect();">Disconnect</button> -->
				</div>
			</div>
		</div>

		<hr>

		<footer>
			<p>&copy; Helpdesk 2013</p>
		</footer>

	</div>
	<!-- /container -->

	<script src="client/js/vendor/bootstrap.min.js"></script>
	<script>
		$('#video').hide();
		$('#form').hide();
	</script>
</body>
</html>
