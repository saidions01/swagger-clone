package com.xqual.xapiserver;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.AsyncContext;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.json.JSONException;
import org.json.JSONObject;

import com.xqual.xcommon.CLocalization;
import com.xqual.xcommon.CMailFactory;
import com.xqual.xcommon.CNetworkInfo;
import com.xqual.xcommon.IConstants;
import com.xqual.xcommon.IConstantsSql;
import com.xqual.xcommon.IConstantsStyle;
import com.xqual.xcommon.conf_properties.CApplicationConfProperties;
import com.xqual.xcommon.conf_properties.CBugTrackingConfProperties;
import com.xqual.xcommon.conf_properties.CPluginConfProperties;
import com.xqual.xcommon.conf_properties.CRequirementConfProperties;
import com.xqual.xcommon.conf_properties.CSsoConfProperties;
import com.xqual.xcommon.thread.IConstantsThread;
import com.xqual.xcommon.utils.CCalendarUtils;
import com.xqual.xcommon.utils.CTextUtils;
import com.xqual.xcommon.utils.CTraceUtils;
import com.xqual.xcommon.utils.CUtils;
import com.xqual.xcommon.utils.network.CCometRestBackend;
import com.xqual.xcommon.utils.network.CCometRestConnectionManager;
import com.xqual.xcommon.utils.network.CLocalFileFactory;
import com.xqual.xcommon.utils.network.CSmbFactory;
import com.xqual.xstudio.CStudioAttachmentUtils;
import com.xqual.xstudio.CStudioDefectUtils;
import com.xqual.xstudio.CStudioGlobals;
import com.xqual.xstudio.CStudioRequirementUtils;
import com.xqual.xstudio.CStudioUtils;
import com.xqual.xstudio.IStudioConstants;
import com.xqual.xstudio.IStudioConstantsBugTracking;
import com.xqual.xstudio.IStudioConstantsRequirements;
import com.xqual.xstudio.gui.treefactory.CTreeNodeData;
import com.xqual.xstudio.gui.treefactory.tree.ITreeConstants;
import com.xqual.xstudio.gui.treefactory.tree.right.CUserRights;
import com.xqual.xstudio.sql.CStudioSqlConnector;
import com.xqual.xstudio.sql.CStudioSqlFactory;
import com.xqual.xstudio.sql.report.CStudioSqlDefectEngine;
import com.xqual.xstudio.sql.report.CStudioSqlRequirementEngine;
import com.xqual.xstudio.sql.report.CStudioSqlSpecificationEngine;
import com.xqual.xstudio.sql.report.CStudioSqlTaskEngine;
import com.xqual.xstudio.sql.report.CStudioSqlTestplanEngine;
import com.xqual.xstudio.sql.transactional.CStudioSqlTransactionalEngine;
import com.xqual.xstudio.sql.tree.IStudioTreeStatements;
import com.xqual.xstudio.thread.bg_sqlFreeConnections.CThreadFreeConnections;
import com.xqual.xstudio.thread.process_daily.CProcessDaily;

@WebServlet(asyncSupported=true)
public class CServer extends HttpServlet implements IConstants, IStudioConstants, IConstantsStyle, IStudioConstantsBugTracking, IStudioConstantsRequirements, IConstantsThread {
	
	public static final String API_VERSION = "3";

	public static boolean RESTART_REQUIRED = false;
	public static boolean NIGHTLY_RESTART = false;

	public static String SERVLET_VERSION = "";
	// Tomcat 6 = Servlet 2.5
	// Tomcat 7 = Servlet 3.0 (asynchronous request processing, programmatically-defined servlets and filters, and annotations for defining servlets and filters)
	// Tomcat 8 = Servlet 3.1 (ability to define Servlets and JSP pages using annotations, programmatic configuration, and asynchronous processing)
	// Tomcat 9 = Servlet 4.0 (HTTP/2 support, the ability to define Servlets and Filters using annotations, and the ability to perform non-blocking I/O)

	public static final int CATALINA_LOG_MAX_SIZE = 1024; // or Integer.MAX_VALUE;
	//public static final int CATALINA_LOG_MAX_SIZE = Integer.MAX_VALUE; // DEBUG MODE
	
	// userId --> list of async contexts
	// is a user is logged several times, it has several async context associated with to get push notifications
	public static volatile LinkedHashMap<Integer, List<AsyncContext>> pushContextMap = new LinkedHashMap<Integer, List<AsyncContext>>();
	public static volatile Vector<HttpSession>                        sessionVector  = new Vector<HttpSession>();
	
	// threads
	private static CThreadFreeConnections threadFreeConnections = null;
	private static CProcessDaily dailyProcess = null;
	
	// cache of the trees per team: teamId --> (treeType+options --> tree json)
	public static volatile HashMap<Integer, HashMap<String, String>> cacheLastRefreshTreeMap = new HashMap<Integer, HashMap<String, String>>();
	// cache of the trees per team: teamId --> (treeType+options --> timeInMillisUTC)
	public static volatile HashMap<Integer, HashMap<String, Long>>   cacheLastRefreshTimeMap = new HashMap<Integer, HashMap<String, Long>>();
	// track last access-rights updates per team: teamId --> timeInMillisUTC
	public static volatile HashMap<Integer, Long>                    teamLastAccessRightsChangeTimeMap = new HashMap<Integer, Long>();
	// track last change a the tree structures (category, company, folders) treeType --> timeInMillisUTC)
	public static volatile HashMap<Short, Long>                      treeStructureLastchangeTimeMap = new HashMap<Short, Long>();
	
	public static Vector<String> blacklistedTokens = new Vector<String>();
	
	// this is used to store time-consuming request's requestIds - to ignore duplicate requests (sometimes done by the browser after a certain timeout)
	// the boolean is actually never used but HashMaps provide O(1) average time complexity for containsKey() operations, making it extremely efficient to check if a request ID has been processed before
	public ConcurrentMap<String, Long> idempotencyStore = new ConcurrentHashMap<>(); // requestId - timestamp
	
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		addHeadersToResponse(response);

		response.setContentType("application/json; charset=utf-8");
		
	    String output = "{\"result\": \"failure\", \"message\": \"Unexpected exit (i.e. invalid command argument)\"}";
	    
	    String command = getCommand(request);

	    System.out.println(">>>>>>>>>>>> GET COMMAND = " + command);

	    // trick to adapt automatically the response content-type to HTML when retrieving any summary
	    /*
	    if (command.startsWith("get") && command.endsWith("Summary")) {
	    	//System.out.println("- - - -  returning HTML...");
	    	response.setContentType("text/html; charset=utf-8");
	    }
	    */
	    
	    // +==============================+
	    // |  NO AUTHENTICATION REQUIRED  |
	    // +==============================+
	    if (command.equalsIgnoreCase("getInfo")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - getInfo()");
		    output = CApiXStudioWeb_Common.getInfo();
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getInfo() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
	    }
	   	
    	if (command.equalsIgnoreCase("getLicenseWarning")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - getLicenseWarning()");
		    output = CApiXStudioWeb_Common.getLicenseWarning();
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getLicenseWarning() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
    	}
    	
    	if (command.equalsIgnoreCase("getLicense")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - getLicense()");
		    output = CApiXStudioWeb_Common.getLicense();
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getLicense() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
    	}

	    if (command.equalsIgnoreCase("checkDatabaseSchemaCompatibility")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - checkDatabaseSchemaCompatibility()");
		    try {
				output = CApiXStudioWeb_Common.checkDatabaseSchemaCompatibility();
				
		    	updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
		    	
			} catch (Exception e) { // If there are some problems in the settings (i.e. invalid JDBC drivers) this will fail in an ugly manner
				output = CRestUtils.formatError("Invalid settings or connection to the database impossible!");
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE); // ERROR 503
			}

		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  checkDatabaseSchemaCompatibility() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
	    }

    	if (command.equalsIgnoreCase("getSsoSettings")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - getSsoSettings()");
		    output = CApiXStudioWeb_Common.getSsoSettings();
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getSsoSettings() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
    	}
 
	    // +==============================+
	    // |    AUTHENTICATION REQUIRED   |
	    // +==============================+
	    
	    
	    // At this stage we MUST have a valid session with a user properly authenticated
	    // BUT we check if it has timed out
	    // We test here if the session include the "user_id" and "localization" attributes
	    if (isSessionTimedOut(request, response, !command.equalsIgnoreCase("registerForPushNotification"))) { // we track activity on all the command except "registerForPushNotification"
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "SESSION TIMED OUT");
	    	response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // ERROR 401
		    PrintWriter out = response.getWriter();
			out.println("{\"result\": \"failure\", \"message\": \"Session expired or invalidated!\"}");
			return;
	    }

	    // necessarily non-null and valid if we get to this stage
	    HttpSession session = request.getSession(false);  // if current session does not exist, then it does nothing.
	    
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / LastAccessedTime (poll filtered) = " + CCalendarUtils.millisTimeToDateString((Long)session.getAttribute("last_access_time")));
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / user_id                          = " + session.getAttribute("user_id"));
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] GET / only_update_user_password        = " + session.getAttribute("only_update_user_password"));

		// At this stage we MUST have a valid session with a user properly authenticated
		// but if we have the session attribute "only_update_user_password", only updateUserPassword() should be usable
		if (session.getAttribute("only_update_user_password") != null) {
		    Boolean onlyUpdateChangePassword = (Boolean)session.getAttribute("only_update_user_password");
		    if (onlyUpdateChangePassword == true) {
		    	response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // ERROR 401
			    PrintWriter out = response.getWriter();
				out.println("{\"result\": \"failure\", \"message\": \"Session authorizing only updateChangePassword() calls!\"}");
				return;
		    }
		}

	    // +-----------------------+
	    // |  PUSH NOTIFICATIONS   |
	    // +-----------------------+
	    
	    // special case of the long-polling push notification
	    if (command.equalsIgnoreCase("registerForPushNotification")) {
	        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - registerForPushNotification()");
	        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Servlet version supported: " + SERVLET_VERSION);

	        CApiCommon.printContextStoreContent(session, "BEFORE REGISTRATION");

	        /*
	        Tomcat 6 : Servlet 2.5
	        Tomcat 7 : Servlet 3.0
	        Tomcat 8 : Servlet 3.1
	        Tomcat 9 : Servlet 4.0
	        */
	        if (this.SERVLET_VERSION.startsWith("1") || this.SERVLET_VERSION.startsWith("2")) {
	        	// Asynchronous call not supported
			    PrintWriter out = response.getWriter();
			    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			    out.println(CRestUtils.formatError("Asynchronous call not supported by Servlet " + SERVLET_VERSION + ". Please update your Servlet container (tomcat)."));
	        	
	        } else if (this.SERVLET_VERSION.startsWith("3") || this.SERVLET_VERSION.startsWith("4") || this.SERVLET_VERSION.startsWith("5")) {
		        int thisUserId = -1;
		        CLocalization thisLocalization;
		        try {
		        	thisUserId = Integer.parseInt(""+session.getAttribute("user_id"));
		        	thisLocalization = (CLocalization)session.getAttribute("localization");
				} catch (NumberFormatException e) {
					CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "registerForPushNotification() ERROR !!! USER not logged in yet");
					PrintWriter out = response.getWriter();
			    	response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			    	out.println(CRestUtils.formatError("Asynchronous call not supported for not-logged-yet users: " + session.getAttribute("user_id")));
					return;
				}

		        response.setContentType("application/json; charset=utf-8");
		        response.setCharacterEncoding("utf-8");
		        response.setStatus(HttpServletResponse.SC_ACCEPTED); // status 202
				response.flushBuffer(); // returns the response with ERR_INCOMPLETE_CHUNKED_ENCODING

				// possibly clear if there are some invalid contexts in the store (old contexts timed out when the session was closed)
				CApiCommon.clearAllInvalidContextsStored();
				
				// create and start the new AsyncContext
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "registerForPushNotification() for user " + thisUserId + " in session " + session.getId());
		        final AsyncContext newAsyncContext = request.startAsync(request, response); // startAsync() returns an AsyncContext object which holds the request and response objects.
		        newAsyncContext.setTimeout(4*60*1000); // 4 minutes timeout (after this, a response is sent with a "ERR_INCOMPLETE_CHUNKED_ENCODING 202 (Accepted)" error)
		        /*
		        newAsyncContext.addListener(new AsyncListener() {
		            @Override
		            public void onStartAsync(final AsyncEvent event) throws IOException {
		                // something
		            }

		            @Override
		            public void onComplete(final AsyncEvent event) throws IOException {
		                //onDone(event);
		            }

		            @Override
		            public void onTimeout(final AsyncEvent event) throws IOException {
		                //onDone(event);
	                     HttpServletResponse response = (HttpServletResponse) event.getSuppliedResponse();
	                    if (!response.isCommitted()) {
	                        response.sendError(503);
	                    }
	                    HttpSession originatorAsyncContextSession = ((HttpServletRequest)event.getAsyncContext().getRequest()).getSession();
	                    if (originatorAsyncContextSession.getServletContext().getRE) {
	                    	System.out.println(" TIMING OUT FROM SESSION ");
	                    }
	                    
						//String thisAsyncContextSessionId = ((HttpServletRequest)asyncContext.getRequest()).getSession().getId();
						//if (thisAsyncContextSessionId != null && thisAsyncContextSessionId.equals(sessionId)) {
		            }

		            @Override
		            public void onError(final AsyncEvent event) throws IOException {
		                //onDone(event);
		            }

		            /*
		            private void onDone(final AsyncEvent event) {
		                // something
		            }
		            
		        });*/

		        synchronized(pushContextMap) {
			        List<AsyncContext> listOfContextsForThisUser = pushContextMap.get(thisUserId);
			        if (listOfContextsForThisUser == null) {
				        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "registerForPushNotification() no already stored any AsyncContexts for this user");
			        	List<AsyncContext> thisUserAsyncContextList = new LinkedList<AsyncContext>();
			        	thisUserAsyncContextList.add(newAsyncContext);
			        	pushContextMap.put(thisUserId, thisUserAsyncContextList);
			        } else {
				        // PROTECTION: ensure there is no remaining context for THIS USER AND THIS SESSION in the store (valid or invalid)
			        	CApiCommon.clearSessionContextsStored(session.getId(), thisUserId);
			        	
			        	listOfContextsForThisUser = pushContextMap.get(thisUserId); // get the new list (which may be empty now)
			        	listOfContextsForThisUser.add(newAsyncContext);
			        	pushContextMap.put(thisUserId, listOfContextsForThisUser);
			        }
		        }
	        } else {
	        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT treated Servlet version: " + SERVLET_VERSION);
	        }

	        CApiCommon.printContextStoreContent(session, "AFTER REGISTRATION");

			return; // do NOT return anything !!!
	    }

	    // +-----------------------+
	    // |      STANDARD API     |
	    // +-----------------------+
	    
	    // Session Initialization
	    try {

	    	if (command.equalsIgnoreCase("getSettings")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSettings()");
			    output = CApiXStudioWeb_Common.getSettings(session);
			    
		    } else if (command.equalsIgnoreCase("getAuditLog")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAuditLog()");
			    output = CApiXStudioWeb_Common.getAuditLog(session);
			    
		    } else if (command.equalsIgnoreCase("getSignatureLog")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSignatureLog()");
			    output = CApiXStudioWeb_Common.getSignatureLog(session);
	
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getServerSettingsForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getServerSettingsForm()");
			    output = CApiXStudioWeb_Common.getServerSettingsForm(session);

		    } else if (command.equalsIgnoreCase("getSsoSettingsForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSsoSettingsForm()");
			    output = CApiXStudioWeb_Common.getSsoSettingsForm();
			    
		    } else if (command.equalsIgnoreCase("getUserPreferencesForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserPreferencesForm()");
			    output = CApiXStudioWeb_Common.getUserPreferencesForm(session); // uses the user_id attribute in the session
			    

		    } else if (command.equalsIgnoreCase("isServerRestartRequired")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - isServerRestartRequired()");
			    output = CApiXStudioWeb_Common.isServerRestartRequired(session);
			    

		    } else if (command.equalsIgnoreCase("isNextServerRestartNightly")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - isNextServerRestartNightly()");
			    output = CApiXStudioWeb_Common.isNextServerRestartNightly(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getEncryptedPassword")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getEncryptedPassword()");
			    output = CApiXStudioWeb_Common.getEncryptedPassword(session, request.getParameter("username"), request.getParameter("password"));
			    CUtils.sleep(500);
			    
			    
		    } else if (command.equalsIgnoreCase("getEncryptedString")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getEncryptedString()");
			    output = CApiXStudioWeb_Common.getEncryptedString(session, request.getParameter("input"));
			    CUtils.sleep(1000);
			    



			    
			    
			// User Action Rights
		    } else if (command.equalsIgnoreCase("getUserActionRights")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserActionRights()");
			    output = CApiCommon.getUserActionRights(session);
			    
	
		    // User Access Rights
		    } else if (command.equalsIgnoreCase("getUserAccessRights")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserAccessRights()");
			    output = CApiCommon.getUserAccessRights(session);
			    
	
	

		    // Node name
		    } else if (command.equalsIgnoreCase("getNodeName")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNodeName()");
			    output = CApiXStudioWeb_Common.getNodeName(session, request.getParameter("nodeType"), request.getParameter("nodeId"));
			    
		
		    // Node names
		    } else if (command.equalsIgnoreCase("getNodeNames")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNodeNames()");
			    output = CApiXStudioWeb_Common.getNodeNames(session, request.getParameter("nodeTypes"), request.getParameter("nodeIds"));
			    
			

			 // Used for community license verification (i.e. in XAgent)
			    
		    } else if (command.equalsIgnoreCase("getDatabaseLatestTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDatabaseLatestTime()");
			    output = CApiXStudioWeb_Common.getDatabaseLatestTime(session);
			    

		    // +-----------------------+
		    // |     ADMINISTRATION    |
		    // +-----------------------+

		    // orphan attachments
		    } else if (command.equalsIgnoreCase("sanitizeOrphanAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - sanitizeOrphanAttachments()");
			    output = CApiXStudioWeb_Common.sanitizeOrphanAttachments(session);
				    
			    
			    
			    

			// Attachments
		    } else if (command.equalsIgnoreCase("getAttachmentRevisions")) { // get all available revision numbers for one specific attachment
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAttachmentRevisions()");
			    output = CApiXStudioWeb_Common.getAttachmentRevisions(session, request.getParameter("nodeType"), request.getParameter("attachmentId"));
			    
	
		    } else if (command.equalsIgnoreCase("downloadAttachment")) { // shortcut to download last revision of the attachment
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadAttachment()");
			    output = CApiXStudioWeb_Common.downloadAttachment(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("attachmentId"));
			    
	
		    } else if (command.equalsIgnoreCase("downloadAttachmentRevision")) { // download a specific revision of an attachment
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadAttachmentRevision()");
			    output = CApiXStudioWeb_Common.downloadAttachmentRevision(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("attachmentId"), request.getParameter("revision"));
			    
	
	
			// Embedded images
		    } else if (command.equalsIgnoreCase("downloadEmbeddedImage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadEmbeddedImage()");
			    output = CApiXStudioWeb_Common.downloadEmbeddedImage(session, response, request.getParameter("embeddedImageId"));
			    
	
			// -----------
			// Search
			// -----------
		    } else if (command.equalsIgnoreCase("getSearchFilters")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSearchFilters()");
			    output = CApiXStudioWeb_Common.getSearchFilters(session, request.getParameter("nodeType"));
			    

			// -------------
			// Default names
			// -------------
		    } else if (command.equalsIgnoreCase("getDefaultUniqueName")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDefaultUniqueName()");
			    output = CApiXStudioWeb_Common.getDefaultUniqueName(session, request.getParameter("nodeType"), request.getParameter("originalName"), request.getParameter("parentId"), request.getParameter("prefix"));
			    
			    
			// -------------
			// Utilities
			// -------------
			// just for test
		    } else if (command.equalsIgnoreCase("getDescendantFolders")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDescendantFolders()");
			    output = CApiXStudioWeb_Common.getDescendantFolders(session, request.getParameter("parentFolderId"));

			// -------------
			// Marketplace
			// -------------
			    
		    } else if (command.equalsIgnoreCase("getMarketplaceCatalog")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getMarketplaceCatalog()");
			    output = CApiXStudioWeb_Common.getMarketplaceCatalog(session);

			// -----------
			// Reports
			// -----------
		    } else if (command.equalsIgnoreCase("getReportForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReportForm()");
			    output = CApiXStudioWeb_Common.getReportForm(session, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("fileExtension"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("downloadZippedHtmlReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadZippedHtmlReport()");
			    
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);
		        
		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for downloadZippedHtmlReport()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Common.downloadZippedHtmlReport(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("reportType"), request.getParameter("reportStyle"), request.getParameter("connectorIndex"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
			    
			    
		    } else if (command.equalsIgnoreCase("downloadPdfReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadPdfReport()");
			    
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);
		        
		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for downloadPdfReport()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Common.downloadPdfReport(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("reportType"), request.getParameter("reportStyle"), request.getParameter("connectorIndex"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
			
		    } else if (command.equalsIgnoreCase("downloadWordReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadWordReport()");
			    
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  dempotencyStore = " + idempotencyStore);

		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for downloadWordReport()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Common.downloadWordReport(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("reportType"), request.getParameter("reportStyle"), request.getParameter("connectorIndex"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
			
		    // -----------------------------------
			// hardcoded / not customizable reports
		    // ------------------------------------
			    
		    // reportType is optional in Xml (default = 'Raw_data')
		    // - Raw_data (sut, req; spec, tesplan, defect, report, task, expl)
		    // - JUnit (session)
		    } else if (command.equalsIgnoreCase("downloadXmlReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadXmlReport()");
			    
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);

		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for downloadXmlReport()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Common.downloadXmlReport(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("reportType"), request.getParameter("connectorIndex"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }

		    // reportType is optional in Excel (default = 'Raw_data')
		    // - Raw_data (sut, req; spec, tesplan, defect)
		    // - Offline_manual_execution (campaign only)
		    } else if (command.equalsIgnoreCase("downloadExcelReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadExcelReport()");

		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);

		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for downloadExcelReport()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Common.downloadExcelReport(session, response, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("reportType"), request.getParameter("connectorIndex"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
			    
			    
			    
			// --------
		    // Timetags
			// --------
		    } else if (command.equalsIgnoreCase("getAllTimetags")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAllTimetags()");
			    output = CApiXStudioWeb_Common.getAllTimetags(session);
			    
	
	
	
			// Custom Fields
		    } else if (command.equalsIgnoreCase("getCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCustomFields()");
			    output = CApiXStudioWeb_Common.getCustomFields(session, request.getParameter("nodeType"));
			    
	
		    } else if (command.equalsIgnoreCase("getCustomField")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCustomField()");
			    output = CApiXStudioWeb_Common.getCustomField(session, request.getParameter("nodeType"), request.getParameter("customFieldType"), request.getParameter("customFieldId"));
			    
	
			    
	
			// Search
		    } else if (command.equalsIgnoreCase("getNodeTreeType")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNodeTreeType()");
			    output = CApiXStudioWeb_Common.getNodeTreeType(session, request.getParameter("nodeType"), request.getParameter("nodeId"));
			    
	
	
	
			// Definitions
			    
		    } else if (command.equalsIgnoreCase("getDefinitions")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDefinitions()");
		    	output = CApiXStudioWeb_Common.getDefinitions(session);
		    	

		    } else if (command.equalsIgnoreCase("getDefinitionForm")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDefinitionForm()");
		    	output = CApiXStudioWeb_Common.getDefinitionForm(session);
		    
		    	
		    	
		    	
		    	
		    // Signatures
		    	
		    } else if (command.equalsIgnoreCase("getSignatureForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSignatureForm()");
			    output = CApiXStudioWeb_Common.getSignatureForm(session);
			    

			    
			    
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Folders
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getFolderDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderDetails()");
			    output = CApiXStudioWeb_Common.getFolderDetails(session, request.getParameter("folderId"));
			    
	
		    } else if (command.equalsIgnoreCase("getFolderSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderSummary()");
		        output = CApiXStudioWeb_Common.getFolderSummary(session, request.getParameter("folderId"));
			    

		    } else if (command.equalsIgnoreCase("getFolderCompany")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderCompany()");
			    output = CApiXStudioWeb_Common.getFolderCompany(session, request.getParameter("folderId"));
			    
	
		    } else if (command.equalsIgnoreCase("getFolderCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderCategory()");
			    output = CApiXStudioWeb_Common.getFolderCategory(session, request.getParameter("folderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getExternalFolderDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExternalFolderDetails()");
			    output = CApiXStudioWeb_Common.getExternalFolderDetails(session, request.getParameter("connectorIndex"), request.getParameter("folderId"), request.getParameter("treeType"));
			    
	
		    } else if (command.equalsIgnoreCase("getFolderForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderForm()");
			    output = CApiXStudioWeb_Common.getFolderForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getFolderAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderAttachments()");
			    output = CApiXStudioWeb_Common.getFolderAttachments(session, request.getParameter("folderId"));
			    
	
		    } else if (command.equalsIgnoreCase("getFolderInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderInheritedAttachments()");
			    output = CApiXStudioWeb_Common.getFolderInheritedAttachments(session, request.getParameter("folderId"));
			    
	
		    } else if (command.equalsIgnoreCase("getFolderTreeStructure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFolderTreeStructure()");
			    output = CApiXStudioWeb_Common.getFolderTreeStructure(session);
			    
			
			    
			    
		    } else if (command.equalsIgnoreCase("getCompanyDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCompanyDetails()");
			    output = CApiXStudioWeb_Common.getCompanyDetails(session, request.getParameter("companyId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getCompanySummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCompanySummary()");
			    output = CApiXStudioWeb_Common.getCompanySummary(session, request.getParameter("companyId"));
			    
	
		    } else if (command.equalsIgnoreCase("getCompanyForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCompanyForm()");
			    output = CApiXStudioWeb_Common.getCompanyForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getCompanyAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCompanyAttachments()");
		        output = CApiXStudioWeb_Common.getCompanyAttachments(session, request.getParameter("companyId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getTreeFilter")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTreeFilter()");
			    output = CApiXStudioWeb_Common.getTreeFilter(session, request.getParameter("treeFilterId"));
			    
	
		    } else if (command.equalsIgnoreCase("getTreeFilterForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTreeFilterForm()");
			    output = CApiXStudioWeb_Common.getTreeFilterForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getTreeFilters")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTreeFilters()");
			    output = CApiXStudioWeb_Common.getTreeFilters(session, request.getParameter("treeType"));
			    
	
	
			    
			    
		    } else if (command.equalsIgnoreCase("getCategoryDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategoryDetails()");
			    output = CApiXStudioWeb_Common.getCategoryDetails(session, request.getParameter("categoryId"));
			    
	
		    } else if (command.equalsIgnoreCase("getCategoryJarName")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategoryJarName()");
		        output = CApiXStudioWeb_Common.getCategoryJarName(session, request.getParameter("categoryId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getCategoryTestsWithCanonicalPath")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategoryTestsWithCanonicalPath()");
		        output = CApiXStudioWeb_Common.getCategoryTestsWithCanonicalPath(session, request.getParameter("categoryId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getCategorySummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategorySummary()");
			    output = CApiXStudioWeb_Common.getCategorySummary(session, request.getParameter("categoryId"));
			    
	
		    } else if (command.equalsIgnoreCase("getCategoryForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategoryForm()");
			    output = CApiXStudioWeb_Common.getCategoryForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getCategoryAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCategoryAttachments()");
		        output = CApiXStudioWeb_Common.getCategoryAttachments(session, request.getParameter("categoryId"));
			    
			    
	
	
			    
			    
		    } else if (command.equalsIgnoreCase("getMessageForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getMessageForm()");
			    output = CApiXStudioWeb_Common.getMessageForm(session);
			    
				
		    } else if (command.equalsIgnoreCase("getDiscussion")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDiscussion()");
			    output = CApiXStudioWeb_Common.getDiscussion(session, request.getParameter("nodeType"), request.getParameter("nodeId"));
			    
	
		    } else if (command.equalsIgnoreCase("getDiscussionNotifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDiscussionNotifications()");
			    output = CApiXStudioWeb_Common.getDiscussionNotifications(session, request.getParameter("userId"));
			    

		    } else if (command.equalsIgnoreCase("hasDiscussionToBeNotified")) { // return true if there is a notification for the current user on a specific object
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - hasDiscussionToBeNotified()");
			    output = CApiXStudioWeb_Common.hasDiscussionToBeNotified(session, request.getParameter("nodeType"), request.getParameter("nodeId"), request.getParameter("userId"));
			    


			// ----------------------------------------------------------------------------------------------------------------------------------------
			// SUTs
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getSutsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutsTree()");
		    	output = CApiXStudioWeb_Sut.getSutsTree(session, request.getParameter("treeFilter"), request.getParameter("forceRetrievingResults"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));

		    } else if (command.equalsIgnoreCase("getSutFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutFreezeStatus()");
		    	output = CApiXStudioWeb_Sut.getSutFreezeStatus(session, request.getParameter("sutId"));

		    } else if (command.equalsIgnoreCase("getSutForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutForm()");
			    output = CApiXStudioWeb_Sut.getSutForm(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getSutDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutDetails()");
		    	output = CApiXStudioWeb_Sut.getSutDetails(session, request.getParameter("sutId"), request.getParameter("frozen"));
		    	
	
		    } else if (command.equalsIgnoreCase("getSutSummary")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutSummary()");
		    	output = CApiXStudioWeb_Sut.getSutSummary(session, request.getParameter("sutId"));
		    	
	
		    } else if (command.equalsIgnoreCase("getSutDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutDetailsRevision()");
			    output = CApiXStudioWeb_Sut.getSutDetailsRevision(session, request.getParameter("sutId"), request.getParameter("revision"));
			    
			    
			    
		    	
		    } else if (command.equalsIgnoreCase("getSutProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutProgress()");
			    output = CApiXStudioWeb_Sut.getSutProgress(session, request.getParameter("sutId"));
			    
		    	
		    } else if (command.equalsIgnoreCase("getSutRadar")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutRadar()");
			    output = CApiXStudioWeb_Sut.getSutRadar(session, request.getParameter("sutId"));
			    
	
		    } else if (command.equalsIgnoreCase("getSutPyramid")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutPyramid()");
			    output = CApiXStudioWeb_Sut.getSutPyramid(session, request.getParameter("sutId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getSutFolder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutFolder()");
			    output = CApiXStudioWeb_Sut.getSutFolder(session, request.getParameter("sutId"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSutResultsTestProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsTestProgress()");
			    output = CApiXStudioWeb_Sut.getSutResultsTestProgress(session, request.getParameter("sutId"), request.getParameter("startDate"), request.getParameter("stopDate"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutResultsTestcaseProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsTestcaseProgress()");
			    output = CApiXStudioWeb_Sut.getSutResultsTestcaseProgress(session, request.getParameter("sutId"), request.getParameter("startDate"), request.getParameter("stopDate"));
			    

		    } else if (command.equalsIgnoreCase("getSutResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsTests()");
			    output = CApiXStudioWeb_Sut.getSutResultsTests(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsTestcases()");
			    output = CApiXStudioWeb_Sut.getSutResultsTestcases(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsSteps()");
			    output = CApiXStudioWeb_Sut.getSutResultsTestcases(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsHistory()");
			    output = CApiXStudioWeb_Sut.getSutResultsHistory(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutResultsRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsRequirements()");
			    output = CApiXStudioWeb_Sut.getSutResultsRequirements(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutResultsRequirementsSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsRequirementsSummary()");
			    output = CApiXStudioWeb_Sut.getSutResultsRequirementsSummary(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutResultsRequirementsPerType")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutResultsRequirementsPerType()");
			    output = CApiXStudioWeb_Sut.getSutResultsRequirementsPerType(session, request.getParameter("sutId"));
			    

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSutCoverageRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "\n\n\n[" + session.getId() + "] ==>  GET - getSutCoverageRequirements()");
			    output = CApiXStudioWeb_Sut.getSutCoverageRequirements(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutCoverageSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutCoverageSpecifications()");
			    output = CApiXStudioWeb_Sut.getSutCoverageSpecifications(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutCoverageTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutCoverageTests()");
			    output = CApiXStudioWeb_Sut.getSutCoverageTests(session, request.getParameter("sutId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSutLinkedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutLinkedRequirements()");
			    output = CApiXStudioWeb_Sut.getSutLinkedRequirements(session, request.getParameter("sutIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutRelatedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutRelatedRequirements()");
			    output = CApiXStudioWeb_Sut.getSutRelatedRequirements(session, request.getParameter("sutIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutLinkedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutLinkedSpecifications()");
			    output = CApiXStudioWeb_Sut.getSutLinkedSpecifications(session, request.getParameter("sutIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutLinkedTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutLinkedTests()");
			    output = CApiXStudioWeb_Sut.getSutLinkedTests(session, request.getParameter("sutIds"));
			    
			    
			    
			    

		    } else if (command.equalsIgnoreCase("getSutFolderFullTraceability")) { 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutFolderFullTraceability()");
			    output = CApiXStudioWeb_Common.getSutFolderFullTraceability(session, request.getParameter("folderId")); // folderId can be root or a normal folder
			    
		    } else if (command.equalsIgnoreCase("getSutFullTraceability")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutFullTraceability()");
			    output = CApiXStudioWeb_Common.getSutFullTraceability(session, request.getParameter("sutIds")); // can be 1 or N SUTs
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSutDefectsFoundInManually")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutDefectsFoundInManually()");
			    output = CApiXStudioWeb_Sut.getSutDefectsFoundInManually(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutDefectsFoundInThroughTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutDefectsFoundInThroughTests()");
			    output = CApiXStudioWeb_Sut.getSutDefectsFoundInThroughTests(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("getSutDefectsFixedIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutDefectsFoundInManually()");
			    output = CApiXStudioWeb_Sut.getSutDefectsFixedIn(session, request.getParameter("sutId"));
			    



		    	
		    } else if (command.equalsIgnoreCase("getSutCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutCustomFields(session, request.getParameter("sutId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("_getSutBooleanCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSutBooleanCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutBooleanCustomFields(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("_getSutIntegerCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSutIntegerCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutIntegerCustomFields(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("_getSutStringCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSutStringCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutStringCustomFields(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("_getSutHtmlCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSutHtmlCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutHtmlCustomFields(session, request.getParameter("sutId"));
			    

		    } else if (command.equalsIgnoreCase("_getSutStringChoiceCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSutStringChoiceCustomFields()");
			    output = CApiXStudioWeb_Sut.getSutStringChoiceCustomFields(session, request.getParameter("sutId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getSutAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutAttachments()");
		        output = CApiXStudioWeb_Sut.getSutAttachments(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutInheritedAttachments()");
		        output = CApiXStudioWeb_Sut.getSutInheritedAttachments(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutRevisions()");
		        output = CApiXStudioWeb_Sut.getSutRevisions(session, request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutChanges()");
		        output = CApiXStudioWeb_Sut.getSutChanges(session, request.getParameter("sutId"));
			    
			    
			    
			    
			    
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Requirements
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getRequirementsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementsTree()");
		        System.out.println("- - - - filter = " + request.getParameter("treeFilter"));
		        
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);

		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for getRequirementsTree()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
					    output = CApiXStudioWeb_Requirement.getRequirementsTree(session, request.getParameter("treeFilter"), request.getParameter("onlyIntegrated"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
			    
		    } else if (command.equalsIgnoreCase("getRequirementFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementFreezeStatus()");
		    	output = CApiXStudioWeb_Requirement.getRequirementFreezeStatus(session, request.getParameter("requirementId"));
		    	
			    
		    } else if (command.equalsIgnoreCase("getRequirementForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementForm()");
			    output = CApiXStudioWeb_Requirement.getRequirementForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getGenericRequirementForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getGenericRequirementForm()");
			    output = CApiXStudioWeb_Requirement.getGenericRequirementForm(session);
			    

		    } else if (command.equalsIgnoreCase("getExternalRequirementForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExternalRequirementForm()");
			    output = CApiXStudioWeb_Requirement.getExternalRequirementForm(session, request.getParameter("connectorIndex"), request.getParameter("projectName"));
			    

		    } else if (command.equalsIgnoreCase("getRequirementDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementDetails()");
			    output = CApiXStudioWeb_Requirement.getRequirementDetails(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"), request.getParameter("frozen"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementSummary()");
			    output = CApiXStudioWeb_Requirement.getRequirementSummary(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementDetailsRevision()");
			    output = CApiXStudioWeb_Requirement.getRequirementDetailsRevision(session, request.getParameter("requirementId"), request.getParameter("revision"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementCustomFields()");
			    output = CApiXStudioWeb_Requirement.getRequirementCustomFields(session, request.getParameter("requirementId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementResultsTests()");
			    output = CApiXStudioWeb_Requirement.getRequirementResultsTests(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementResultsTestcases()");
			    output = CApiXStudioWeb_Requirement.getRequirementResultsTestcases(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementResultsSteps()");
			    output = CApiXStudioWeb_Requirement.getRequirementResultsTestcases(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementCoverageSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementCoverageSpecifications()");
			    output = CApiXStudioWeb_Requirement.getRequirementCoverageSpecifications(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementCoverageTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementCoverageTests()");
			    output = CApiXStudioWeb_Requirement.getRequirementCoverageTests(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementLinkedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementLinkedRequirements()");
			    output = CApiXStudioWeb_Requirement.getRequirementLinkedRequirements(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementLinkedSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementLinkedSuts()");
			    output = CApiXStudioWeb_Requirement.getRequirementLinkedSuts(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementLinkedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementLinkedSpecifications()");
			    output = CApiXStudioWeb_Requirement.getRequirementLinkedSpecifications(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementRelatedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementRelatedSpecifications()");
			    output = CApiXStudioWeb_Requirement.getRequirementRelatedSpecifications(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementLinkedTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementLinkedTests()");
			    output = CApiXStudioWeb_Requirement.getRequirementLinkedTests(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));
			    

		    } else if (command.equalsIgnoreCase("getRequirementLinkedBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementLinkedBugs()");
			    output = CApiXStudioWeb_Requirement.getRequirementLinkedBugs(session, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementFolderFullTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementFolderFullTraceability()");
		        if (request.getParameter("connectorIndex") == null) { // used when a connector or a folder is selected
		        	output = CApiXStudioWeb_Common.getRequirementRootFolderFullTraceability(session);
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	if (strFolderId != null) {
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET MATRIX ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Common.getRequirementConnectorFullTraceability(session, request.getParameter("connectorIndex"));
		        		} else {
		        			//System.out.println("- - - ->> GET MATRIX ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
				        	output = CApiXStudioWeb_Common.getRequirementFolderFullTraceability(session, request.getParameter("connectorIndex"), request.getParameter("folderId"));
		        		}
		        	}
		        }

		    } else if (command.equalsIgnoreCase("getRequirementFullTraceability")) { // can be 1 or N requirements
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementFullTraceability()");
			    output = CApiXStudioWeb_Common.getRequirementFullTraceability(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));

			
			
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementFolderInnerTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementFolderInnerTraceability()");
		        if (request.getParameter("connectorIndex") == null) { // used when a connector or a folder is selected
		        	output = CApiXStudioWeb_Common.getRequirementRootFolderInnerTraceability(session);
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	if (strFolderId != null) {
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET MATRIX ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Common.getRequirementConnectorInnerTraceability(session, request.getParameter("connectorIndex"));
		        		} else {
		        			//System.out.println("- - - ->> GET MATRIX ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
				        	output = CApiXStudioWeb_Common.getRequirementFolderInnerTraceability(session, request.getParameter("connectorIndex"), request.getParameter("folderId"));
		        		}
		        	}
		        }
			    
		    } else if (command.equalsIgnoreCase("getRequirementInnerTraceability")) { // can be 1 or N requirements
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementInnerTraceability()");
			    output = CApiXStudioWeb_Common.getRequirementInnerTraceability(session, request.getParameter("connectorIndex"), request.getParameter("requirementIds"));

			    
			    
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementAttachments()");
			    output = CApiXStudioWeb_Requirement.getRequirementAttachments(session, request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementInheritedAttachments()");
			    output = CApiXStudioWeb_Requirement.getRequirementInheritedAttachments(session, request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementRevisions()");
			    output = CApiXStudioWeb_Requirement.getRequirementRevisions(session, request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementChanges()");
			    output = CApiXStudioWeb_Requirement.getRequirementChanges(session, request.getParameter("requirementId"));
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getRequirementTypes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getRequirementTypes()");
		        output = CApiXStudioWeb_Requirement.getRequirementTypes(session);
			    
			    

			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Specifications
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getSpecificationsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationsTree()");
		    	output = CApiXStudioWeb_Specification.getSpecificationsTree(session, request.getParameter("treeFilter"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
	
		    } else if (command.equalsIgnoreCase("getSpecificationFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationFreezeStatus()");
		    	output = CApiXStudioWeb_Specification.getSpecificationFreezeStatus(session, request.getParameter("specificationId"));
		    	

		    } else if (command.equalsIgnoreCase("getSpecificationForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationForm()");
			    output = CApiXStudioWeb_Specification.getSpecificationForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getSpecificationDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationDetails()");
			    output = CApiXStudioWeb_Specification.getSpecificationDetails(session, request.getParameter("specificationId"), request.getParameter("frozen"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationSummary()");
			    output = CApiXStudioWeb_Specification.getSpecificationSummary(session, request.getParameter("specificationId"));
			    
		    } else if (command.equalsIgnoreCase("getSpecificationDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationDetailsRevision()");
			    output = CApiXStudioWeb_Specification.getSpecificationDetailsRevision(session, request.getParameter("specificationId"), request.getParameter("revision"));
			    
			    

		    
		    
		    
		    } else if (command.equalsIgnoreCase("getSpecificationFolderFullTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationFolderFullTraceability()");
			    output = CApiXStudioWeb_Common.getSpecificationFolderFullTraceability(session, request.getParameter("folderId"));
			    

		    } else if (command.equalsIgnoreCase("getSpecificationFullTraceability")) { // can be 1 or N requirements
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationFullTraceability()");
			    output = CApiXStudioWeb_Common.getSpecificationFullTraceability(session, request.getParameter("specificationIds"));


			
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationCoverageTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationCoverageTests()");
			    output = CApiXStudioWeb_Specification.getSpecificationCoverageTests(session, request.getParameter("specificationId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationLinkedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationLinkedSpecifications()");
			    output = CApiXStudioWeb_Specification.getSpecificationLinkedSpecifications(session, request.getParameter("specificationIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationLinkedSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationLinkedSuts()");
			    output = CApiXStudioWeb_Specification.getSpecificationLinkedSuts(session, request.getParameter("specificationIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationLinkedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationLinkedRequirements()");
			    output = CApiXStudioWeb_Specification.getSpecificationLinkedRequirements(session, request.getParameter("specificationIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationLinkedTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationLinkedTests()");
			    output = CApiXStudioWeb_Specification.getSpecificationLinkedTests(session, request.getParameter("specificationIds"));
			    
	
		    } else if (command.equalsIgnoreCase("getSpecificationLinkedBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationLinkedBugs()");
			    output = CApiXStudioWeb_Specification.getSpecificationLinkedBugs(session, request.getParameter("specificationId"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationResultsTests()");
			    output = CApiXStudioWeb_Specification.getSpecificationResultsTests(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationResultsTestcases()");
			    output = CApiXStudioWeb_Specification.getSpecificationResultsTestcases(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationResultsSteps()");
			    output = CApiXStudioWeb_Specification.getSpecificationResultsTestcases(session, request.getParameter("specificationId"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationCustomFields()");
			    output = CApiXStudioWeb_Specification.getSpecificationCustomFields(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationAttachments()");
		        output = CApiXStudioWeb_Specification.getSpecificationAttachments(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationInheritedAttachments()");
		        output = CApiXStudioWeb_Specification.getSpecificationInheritedAttachments(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationRevisions()");
		        output = CApiXStudioWeb_Specification.getSpecificationRevisions(session, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSpecificationChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSpecificationChanges()");
		        output = CApiXStudioWeb_Specification.getSpecificationChanges(session, request.getParameter("specificationId"));
			    
			    
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Attributes and Parameters
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    } else if (command.equalsIgnoreCase("getAttributes")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAttributes()");
		    	output = CApiXStudioWeb_Test.getAttributes(session);
		    	

		    } else if (command.equalsIgnoreCase("getAttributeForm")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAttributeForm()");
		    	output = CApiXStudioWeb_Test.getAttributeForm(session);
		    	

		    } else if (command.equalsIgnoreCase("getAttributeDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAttributeDetails()");
		    	output = CApiXStudioWeb_Test.getAttributeDetails(session, request.getParameter("attributeId"), request.getParameter("attributeType"));
		    	

		    	
		    	
		    } else if (command.equalsIgnoreCase("getParameters")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getParameters()");
		    	output = CApiXStudioWeb_Test.getParameters(session);
		    	
			    
		    } else if (command.equalsIgnoreCase("getParameterForm")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getParameterForm()");
		    	output = CApiXStudioWeb_Test.getParameterForm(session);
		    	

		    } else if (command.equalsIgnoreCase("getParameterDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getParameterDetails()");
		    	output = CApiXStudioWeb_Test.getParameterDetails(session, request.getParameter("parameterId"), request.getParameter("parameterType"));
		    	


			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Tests
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getTestsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestsTree()");
		    	output = CApiXStudioWeb_Test.getTestsTree(session, request.getParameter("treeFilter"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
	
		    } else if (command.equalsIgnoreCase("getTestFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestFreezeStatus()");
		    	output = CApiXStudioWeb_Test.getTestFreezeStatus(session, request.getParameter("testId"));
		    	

		    } else if (command.equalsIgnoreCase("getTestForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestForm()");
			    output = CApiXStudioWeb_Test.getTestForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getTestDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestDetails()");
			    output = CApiXStudioWeb_Test.getTestDetails(session, request.getParameter("testId"), request.getParameter("frozen"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestSummary()");
			    output = CApiXStudioWeb_Test.getTestSummary(session, request.getParameter("testId"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestDetailsRevision()");
			    output = CApiXStudioWeb_Test.getTestDetailsRevision(session, request.getParameter("testId"), request.getParameter("revision"));
			    
/*
		    } else if (command.equalsIgnoreCase("getTestScope")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestScope()");
			    output = CApiXStudioWeb_Test.getTestScope(session, request.getParameter("testId"), request.getParameter("frozen"));

	
		    } else if (command.equalsIgnoreCase("getTestScopeRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestScopeRevision()");
			    output = CApiXStudioWeb_Test.getTestScopeRevision(session, request.getParameter("testId"), request.getParameter("revision"));
*/

		    } else if (command.equalsIgnoreCase("getTestEstimatedTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestEstimatedTime()");
			    output = CApiXStudioWeb_Test.getTestEstimatedTime(session, request.getParameter("testId"));
			    

		    } else if (command.equalsIgnoreCase("getTestDependencies")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestDependencies()");
			    output = CApiXStudioWeb_Test.getTestDependencies(session);
			    


			    
		    } else if (command.equalsIgnoreCase("getTestAuthor")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestAuthor()");
			    output = CApiXStudioWeb_Test.getTestAuthor(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestDeveloper")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestDeveloper()");
			    output = CApiXStudioWeb_Test.getTestDeveloper(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestLinkedBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestLinkedBugs()");
			    output = CApiXStudioWeb_Test.getTestLinkedBugs(session, request.getParameter("testId"));
			    
			    
			    
		    
		    } else if (command.equalsIgnoreCase("getTestFolderFullTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestFolderFullTraceability()");
			    output = CApiXStudioWeb_Common.getTestFolderFullTraceability(session, request.getParameter("folderId"));
			    

		    } else if (command.equalsIgnoreCase("getTestFullTraceability")) { // can be 1 or N requirements
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestFullTraceability()");
			    output = CApiXStudioWeb_Common.getTestFullTraceability(session, request.getParameter("testIds"));

			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getTestFolderInnerTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestFolderInnerTraceability()");
		        output = CApiXStudioWeb_Common.getTestFolderInnerTraceability(session, request.getParameter("folderId"));
			    
		    } else if (command.equalsIgnoreCase("getTestInnerTraceability")) { // can be 1 or N tests
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestInnerTraceability()");
			    output = CApiXStudioWeb_Common.getTestInnerTraceability(session, request.getParameter("testIds"));

			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getTestLinkedSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestLinkedSuts()");
			    output = CApiXStudioWeb_Test.getTestLinkedSuts(session, request.getParameter("testIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestLinkedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestLinkedRequirements()");
			    output = CApiXStudioWeb_Test.getTestLinkedRequirements(session, request.getParameter("testIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestLinkedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestLinkedSpecifications()");
			    output = CApiXStudioWeb_Test.getTestLinkedSpecifications(session, request.getParameter("testIds"));
			    
		    	

		    } else if (command.equalsIgnoreCase("getTestCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestCampaigns()");
			    output = CApiXStudioWeb_Test.getTestCampaigns(session, request.getParameter("testId"));
			    


			    
		    } else if (command.equalsIgnoreCase("getTestResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestResultsTestcases()");
			    output = CApiXStudioWeb_Test.getTestResultsTestcases(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestResultsSteps()");
			    output = CApiXStudioWeb_Test.getTestResultsTestcases(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestResultsHistory()");
			    output = CApiXStudioWeb_Test.getTestResultsHistory(session, request.getParameter("testId"));
			    
			    
			    
			    
			    
			    
		    	
		    } else if (command.equalsIgnoreCase("getTestCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestCustomFields()");
		        output = CApiXStudioWeb_Test.getTestCustomFields(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestAttachments()");
		        output = CApiXStudioWeb_Test.getTestAttachments(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestInheritedAttachments()");
		        output = CApiXStudioWeb_Test.getTestInheritedAttachments(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestRevisions()");
		        output = CApiXStudioWeb_Test.getTestRevisions(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestChanges()");
		        output = CApiXStudioWeb_Test.getTestChanges(session, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestTestcases()");
		        output = CApiXStudioWeb_Test.getTestTestcases(session, request.getParameter("testId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getTestTypes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestTypes()");
		        output = CApiXStudioWeb_Test.getTestTypes(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getTestAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestAttributes()");
		        output = CApiXStudioWeb_Test.getTestAttributes(session, request.getParameter("testId"));
			    
			    

		    
			    
		    } else if (command.equalsIgnoreCase("getTestAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestAssets()");
		        output = CApiXStudioWeb_Test.getTestAssets(session, request.getParameter("testIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestAssetRule")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestAssetRule()");
		        output = CApiXStudioWeb_Test.getTestAssetRule(session, request.getParameter("testIds"));
			    

		    } else if (command.equalsIgnoreCase("getTestParamsCombinations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestParamsCombinations()");
		        output = CApiXStudioWeb_Test.getTestParamsCombinations(session, request.getParameter("testId"));
			    

			    
			    
			    
			    
			// Used for community license verification (i.e. in XAgent)
			
		    } else if (command.equalsIgnoreCase("getNbTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNbTests()");
			    output = CApiXStudioWeb_Test.getNbTests(session);
			    

			    
			    
			// Test Scanning
		    } else if (command.equalsIgnoreCase("getFirstAvailableArmedScanningJob")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - getFirstAvailableArmedScanningJob()");
			    output = CApiXStudioWeb_Test.getFirstAvailableArmedScanningJob(session, request.getParameter("agentId"));
			    

		    } else if (command.equalsIgnoreCase("getScanningJobStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - getScanningJobStatus()");
			    output = CApiXStudioWeb_Test.getScanningJobStatus(session, request.getParameter("scanningJobId"));
			    

		    } else if (command.equalsIgnoreCase("getScanningJobOutput")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - getScanningJobOutput()");
			    output = CApiXStudioWeb_Test.getScanningJobOutput(session, request.getParameter("scanningJobId"));
			    

			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Testcases
			// ----------------------------------------------------------------------------------------------------------------------------------------
	
		    } else if (command.equalsIgnoreCase("getTestcaseFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseFreezeStatus()");
		    	output = CApiXStudioWeb_Test.getTestcaseFreezeStatus(session, request.getParameter("testcaseId"));
		    	

		    } else if (command.equalsIgnoreCase("getTestcaseForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseForm()");
			    output = CApiXStudioWeb_Test.getTestcaseForm(session, request.getParameter("testId"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseDetails")) { // only standard testcase
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseDetails()");
			    output = CApiXStudioWeb_Test.getTestcaseDetails(session, request.getParameter("testcaseId"), request.getParameter("frozen"));
			    
		    } else if (command.equalsIgnoreCase("getTestcaseOrTestcaseReferencingReusableTestcaseDetails")) { // with an additional test to check if it's a standard testcase or a testcase referencing a reusable testcase
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseOrTestcaseReferencingReusableTestcaseDetails()");
			    output = CApiXStudioWeb_Test.getTestcaseOrTestcaseReferencingReusableTestcaseDetails(session, request.getParameter("testcaseId"), request.getParameter("frozen"));
				
			    
		    } else if (command.equalsIgnoreCase("getTestcaseSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseSummary()");
			    output = CApiXStudioWeb_Test.getTestcaseSummary(session, request.getParameter("testcaseId"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseDetailsRevision()");
			    output = CApiXStudioWeb_Test.getTestcaseDetailsRevision(session, request.getParameter("testcaseId"), request.getParameter("revision"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseProcedure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseProcedure()");
			    output = CApiXStudioWeb_Test.getTestcaseProcedure(session, request.getParameter("testcaseId"), request.getParameter("frozen"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseProcedureRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseProcedureRevision()");
			    output = CApiXStudioWeb_Test.getTestcaseProcedureRevision(session, request.getParameter("testcaseId"), request.getParameter("revision"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseCustomFields()");
		        output = CApiXStudioWeb_Test.getTestcaseCustomFields(session, request.getParameter("testcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestcaseAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseAttachments()");
		        output = CApiXStudioWeb_Test.getTestcaseAttachments(session, request.getParameter("testcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestcaseRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseRevisions()");
		        output = CApiXStudioWeb_Test.getTestcaseRevisions(session, request.getParameter("testcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestcaseChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseChanges()");
		        output = CApiXStudioWeb_Test.getTestcaseChanges(session, request.getParameter("testcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestcaseTest")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseTest()");
		        output = CApiXStudioWeb_Test.getTestcaseTest(session, request.getParameter("testcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getTestcaseParameters")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseParameters()");
		        output = CApiXStudioWeb_Test.getTestcaseParameters(session, request.getParameter("testcaseId"));
			    
			    

		    } else if (command.equalsIgnoreCase("getTestcaseResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseResultsSteps()");
			    output = CApiXStudioWeb_Test.getTestcaseResultsTestcases(session, request.getParameter("testcaseId"));
			    

		    } else if (command.equalsIgnoreCase("getTestcaseResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseResultsHistory()");
			    output = CApiXStudioWeb_Test.getTestcaseResultsHistory(session, request.getParameter("testcaseId"));
			    


		    } else if (command.equalsIgnoreCase("getTestcaseReusableTestcaseId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReusableTestcaseId()");
			    output = CApiXStudioWeb_Test.getTestcaseReusableTestcaseId(session, request.getParameter("testcaseId"));
			    
		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseForm()");
			    output = CApiXStudioWeb_Test.getTestcaseReusableTestcaseForm(session, request.getParameter("testId"));
			    
		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseSummary()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseSummary(session, request.getParameter("testcaseId"));
			    
	
		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseDetails()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseDetails(session, request.getParameter("testcaseId"));
			    

		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseDetailsRevision()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseDetailsRevision(session, request.getParameter("testcaseId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseProcedure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseProcedure()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseProcedure(session, request.getParameter("testcaseId"));
			    

		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseProcedureRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseProcedureRevision()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseProcedureRevision(session, request.getParameter("testcaseId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseRevisions()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseRevisions(session, request.getParameter("testcaseId"));
			    
	
			/* we must call getTestcaseChanges() as what we really want is the change on the referer (the testcase) i.e. the change on the index!
		    } else if (command.equalsIgnoreCase("getTestcaseReferencingReusableTestcaseChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestcaseReferencingReusableTestcaseChanges()");
			    output = CApiXStudioWeb_Test.getTestcaseReferencingReusableTestcaseChanges(session, request.getParameter("testcaseId"));
			    
			*/

			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Exploratory Sessions
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    } else if (command.equalsIgnoreCase("getExploratorySessionForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionForm()");
			    output = CApiXStudioWeb_Campaign.getExploratorySessionForm(session);

		    } else if (command.equalsIgnoreCase("getExploratorySessionDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionDetails()");
		    	output = CApiXStudioWeb_Campaign.getExploratorySessionDetails(session, request.getParameter("exploratorySessionId"));

		    } else if (command.equalsIgnoreCase("getExploratorySessionDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionDetailsRevision()");
		    	output = CApiXStudioWeb_Campaign.getExploratorySessionDetailsRevision(session, request.getParameter("exploratorySessionId"), request.getParameter("revision"));
		    	
		    	
		    } else if (command.equalsIgnoreCase("getExploratorySessionAssignedTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionAssignedTo()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionAssignedTo(session, request.getParameter("exploratorySessionId"));
		        
		    } else if (command.equalsIgnoreCase("getExploratorySessionSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionSut()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionSut(session, request.getParameter("exploratorySessionId"));
			
		    } else if (command.equalsIgnoreCase("getExploratorySessionBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionBugs()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionBugs(session, request.getParameter("exploratorySessionId"));

/*
		    } else if (command.equalsIgnoreCase("getExploratorySessionDataForExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionDataForExecution()");
*/

		    } else if (command.equalsIgnoreCase("getExploratorySessionAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionAttachments()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionAttachments(session, request.getParameter("exploratorySessionId"));
		        
		    } else if (command.equalsIgnoreCase("getExploratorySessionInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionInheritedAttachments()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionInheritedAttachments(session, request.getParameter("exploratorySessionId"));
		        
		    } else if (command.equalsIgnoreCase("getExploratorySessionRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionRevisions()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionRevisions(session, request.getParameter("exploratorySessionId"));
		        
		    } else if (command.equalsIgnoreCase("getExploratorySessionChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionChanges()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionChanges(session, request.getParameter("exploratorySessionId"));

		    } else if (command.equalsIgnoreCase("getExploratorySessionAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExploratorySessionAttributes()");
		        output = CApiXStudioWeb_Campaign.getExploratorySessionAttributes(session, request.getParameter("exploratorySessionId"));
		        

			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Campaigns
			// ----------------------------------------------------------------------------------------------------------------------------------------


		    } else if (command.equalsIgnoreCase("getCampaignsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsTree()");
		    	output = CApiXStudioWeb_Campaign.getCampaignsTree(session, request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
		    	
		    } else if (command.equalsIgnoreCase("getCampaignFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFreezeStatus()");
		    	output = CApiXStudioWeb_Campaign.getCampaignFreezeStatus(session, request.getParameter("campaignId"));
		    	

		    } else if (command.equalsIgnoreCase("getCampaignForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignForm()");
			    output = CApiXStudioWeb_Campaign.getCampaignForm(session);
			    

		    } else if (command.equalsIgnoreCase("getCampaignDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignDetails()");
		    	output = CApiXStudioWeb_Campaign.getCampaignDetails(session, request.getParameter("campaignId"), request.getParameter("frozen"));
		    	

		    } else if (command.equalsIgnoreCase("getCampaignSummary")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignSummary()");
		    	output = CApiXStudioWeb_Campaign.getCampaignSummary(session, request.getParameter("campaignId"));
		    	
		    	
		    } else if (command.equalsIgnoreCase("getCampaignTests")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignTests()");
		    	output = CApiXStudioWeb_Campaign.getCampaignTests(session, request.getParameter("campaignId"));
		    	
		    	
		    	
		    	
		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getCampaignResultsTestProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsTestProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTestProgress(session, request.getParameter("campaignId"), request.getParameter("startDate"), request.getParameter("stopDate"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsTestProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsTestProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTestProgress(session, request.getParameter("folderId"), request.getParameter("startDate"), request.getParameter("stopDate"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsTestProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsTestProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTestProgress(session, request.getParameter("campaignIds"), request.getParameter("startDate"), request.getParameter("stopDate"));


		    } else if (command.equalsIgnoreCase("getCampaignResultsTestcaseProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsTestcaseProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTestcaseProgress(session, request.getParameter("campaignId"), request.getParameter("startDate"), request.getParameter("stopDate"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsTestcaseProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsTestcaseProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTestcaseProgress(session, request.getParameter("folderId"), request.getParameter("startDate"), request.getParameter("stopDate"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsTestcaseProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsTestcaseProgress()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTestcaseProgress(session, request.getParameter("campaignIds"), request.getParameter("startDate"), request.getParameter("stopDate"));
			    

			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getCampaignResultsTests")) { // could be deprecated
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsTests()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTests(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsTests()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTests(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsTests()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTests(session, request.getParameter("campaignIds"));
			    
		    } else if (command.equalsIgnoreCase("getCampaignResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsTestcases()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTestcases(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsTestcases()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTestcases(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsTestcases()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTestcases(session, request.getParameter("campaignIds"));
			    
		    } else if (command.equalsIgnoreCase("getCampaignResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsSteps()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTestcases(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsSteps()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTestcases(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsSteps()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTestcases(session, request.getParameter("campaignIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getCampaignResultsTreeview")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsTreeview()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsTreeview(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsTreeview")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsTreeview()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsTreeview(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsTreeview")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsTreeview()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsTreeview(session, request.getParameter("campaignIds"));


		    } else if (command.equalsIgnoreCase("getCampaignResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsHistory()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsHistory(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsHistory()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsHistory(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsHistory()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsHistory(session, request.getParameter("campaignIds"));
			    

		    } else if (command.equalsIgnoreCase("getCampaignResultsRequirementsSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsRequirementsSummary()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsRequirementsSummary(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsRequirementsSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsRequirementsSummary()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsRequirementsSummary(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsRequirementsSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsRequirementsSummary()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsRequirementsSummary(session, request.getParameter("campaignIds"));
		    	
			    
		    } else if (command.equalsIgnoreCase("getCampaignResultsRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignResultsRequirements()");
			    output = CApiXStudioWeb_Campaign.getCampaignResultsRequirements(session, request.getParameter("campaignId"));
		    } else if (command.equalsIgnoreCase("getCampaignFolderResultsRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignFolderResultsRequirements()");
			    output = CApiXStudioWeb_Campaign.getCampaignFolderResultsRequirements(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getCampaignsResultsRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignsResultsRequirements()");
			    output = CApiXStudioWeb_Campaign.getCampaignsResultsRequirements(session, request.getParameter("campaignIds"));
			    
			    
		    	
			    
			    
			    
			    
		    	
		    } else if (command.equalsIgnoreCase("getCampaignCategories")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignCategories()");
		    	output = CApiXStudioWeb_Campaign.getCampaignCategories(session, request.getParameter("campaignId"));
		    	

		    } else if (command.equalsIgnoreCase("getCampaignSessions")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignSessions()");
		    	output = CApiXStudioWeb_Campaign.getCampaignSessions(session, request.getParameter("campaignId"));
		    	
			
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSuts")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSuts()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSuts(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSutsFullyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSutsFullyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSutsFullyCovered(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSutsPartiallyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSutsPartiallyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSutsPartiallyCovered(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSutsNotCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSutsNotCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSutsNotCovered(session, request.getParameter("campaignId"));
		    	

		    	
		    	
		    } else if (command.equalsIgnoreCase("getCampaignLinkedRequirements")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedRequirements()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedRequirements(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedRequirementsFullyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedRequirementsFullyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedRequirementsFullyCovered(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedRequirementsPartiallyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedRequirementsPartiallyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedRequirementsPartiallyCovered(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedRequirementsNotCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedRequirementsNotCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedRequirementsNotCovered(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("campaignId"));
		    	

		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSpecifications")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSpecifications()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSpecifications(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSpecificationsFullyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSpecificationsFullyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSpecificationsFullyCovered(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSpecificationsPartiallyCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSpecificationsPartiallyCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSpecificationsPartiallyCovered(session, request.getParameter("campaignId"));
		    	

				
		    } else if (command.equalsIgnoreCase("getCampaignLinkedSpecificationsNotCovered")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignLinkedSpecificationsNotCovered()");
		    	output = CApiXStudioWeb_Campaign.getCampaignLinkedSpecificationsNotCovered(session, request.getParameter("campaignId"));
		    	

		    	

			
		    	
		    	
		    } else if (command.equalsIgnoreCase("_runAllStandardArmedSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - _runAllStandardArmedSessions()");
			    output = CApiXStudioWeb_Campaign._runAllStandardArmedSessions(session, request.getParameter("agentId"));
			    
		    	
		    } else if (command.equalsIgnoreCase("_detectFirstAvailableSchedule")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - _detectFirstAvailableSchedule()");
			    output = CApiXStudioWeb_Campaign._detectFirstAvailableSchedule(session, request.getParameter("agentId"));
			    
			    

			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getCampaignAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignAttachments()");
		        output = CApiXStudioWeb_Campaign.getCampaignAttachments(session, request.getParameter("campaignId"));
			    
	
		    } else if (command.equalsIgnoreCase("getCampaignInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignInheritedAttachments()");
		        output = CApiXStudioWeb_Campaign.getCampaignInheritedAttachments(session, request.getParameter("campaignId"));
			    
		    	
			    
			    
			    
			    

		    } else if (command.equalsIgnoreCase("getScheduleForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleForm()");
			    output = CApiXStudioWeb_Campaign.getScheduleForm(session);
			    

		    } else if (command.equalsIgnoreCase("getScheduleDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleDetails()");
		    	output = CApiXStudioWeb_Campaign.getScheduleDetails(session, request.getParameter("scheduleId"), request.getParameter("frozen"));
		    	

		    } else if (command.equalsIgnoreCase("getScheduleScheduling")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleScheduling()");
		    	output = CApiXStudioWeb_Campaign.getScheduleScheduling(session, request.getParameter("scheduleId"), request.getParameter("frozen"));
		    	

		    } else if (command.equalsIgnoreCase("getScheduleOperator")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleOperator()");
		    	output = CApiXStudioWeb_Campaign.getScheduleOperator(session, request.getParameter("scheduleId"));
		    	

		    } else if (command.equalsIgnoreCase("getScheduleAgents")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleAgents()");
		    	output = CApiXStudioWeb_Campaign.getScheduleAgents(session, request.getParameter("scheduleId"));
		    	

		    } else if (command.equalsIgnoreCase("getScheduleSut")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleSut()");
		    	output = CApiXStudioWeb_Campaign.getScheduleSut(session, request.getParameter("scheduleId"));
		    	

		    	
		    } else if (command.equalsIgnoreCase("getScheduleConfigurations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleConfigurations()");
		        output = CApiXStudioWeb_Campaign.getScheduleConfigurations(session, request.getParameter("scheduleId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getScheduleMonitoredServers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleMonitoredServers()");
		        output = CApiXStudioWeb_Campaign.getScheduleMonitoredServers(session, request.getParameter("scheduleId"));
			    

		    } else if (command.equalsIgnoreCase("getScheduleMonitoringConfiguration")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleMonitoringConfiguration()");
		        output = CApiXStudioWeb_Campaign.getScheduleMonitoringConfiguration(session, request.getParameter("scheduleId"));
			    

		    } else if (command.equalsIgnoreCase("getScheduleSessions")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleSessions()");
		    	output = CApiXStudioWeb_Campaign.getScheduleSessions(session, request.getParameter("scheduleId"));
		    	


		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getCampaignAttributes")) { // used when creating a session or a schedule
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignAttributes()");
		        output = CApiXStudioWeb_Campaign.getCampaignAttributes(session, request.getParameter("campaignId"));

		    } else if (command.equalsIgnoreCase("getSessionAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionAttributes()");
		        output = CApiXStudioWeb_Campaign.getSessionAttributes(session, request.getParameter("sessionId"));

		    } else if (command.equalsIgnoreCase("getScheduleAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleAttributes()");
		        output = CApiXStudioWeb_Campaign.getScheduleAttributes(session, request.getParameter("scheduleId"));

		        
		        
		        
		        
		    } else if (command.equalsIgnoreCase("getCampaignParams")) { // used when creating a session or a schedule
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getCampaignParams()");
		        output = CApiXStudioWeb_Campaign.getCampaignParams(session);
			    
		    } else if (command.equalsIgnoreCase("getSessionParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionParams()");
		        output = CApiXStudioWeb_Campaign.getSessionParams(session, request.getParameter("sessionId"));
			    
		    } else if (command.equalsIgnoreCase("getScheduleParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleParams()");
		        output = CApiXStudioWeb_Campaign.getScheduleParams(session, request.getParameter("scheduleId"));
			    
		        
		        
		        
		        
		        
			    
		    } else if (command.equalsIgnoreCase("getScheduleExecutionOptions")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleExecutionOptions()");
		    	output = CApiXStudioWeb_Campaign.getScheduleExecutionOptions(session, request.getParameter("scheduleId"));
		    	


		    } else if (command.equalsIgnoreCase("getScheduleFollowers")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleFollowers()");
		    	output = CApiXStudioWeb_Campaign.getScheduleFollowers(session, request.getParameter("scheduleId"));
		    	

		    } else if (command.equalsIgnoreCase("getScheduleFollowerEmails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduleFollowerEmails()");
		    	output = CApiXStudioWeb_Campaign.getScheduleFollowerEmails(session, request.getParameter("scheduleId"));
		    	


		    	
		    	
		    	
		    	
		    	
		    	
		    	
			    
		    } else if (command.equalsIgnoreCase("getSessionFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionFreezeStatus()");
		    	output = CApiXStudioWeb_Campaign.getSessionFreezeStatus(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionForm()");
			    output = CApiXStudioWeb_Campaign.getSessionForm(session, request.getParameter("campaignId"));
			    
		    	
		    } else if (command.equalsIgnoreCase("getSessionDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionDetails()");
		    	output = CApiXStudioWeb_Campaign.getSessionDetails(session, request.getParameter("sessionId"), request.getParameter("frozen"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionSummary")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionSummary()");
		    	output = CApiXStudioWeb_Campaign.getSessionSummary(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionCampaign")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionCampaign()");
		    	output = CApiXStudioWeb_Campaign.getSessionCampaign(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionOperator")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionOperator()");
		    	output = CApiXStudioWeb_Campaign.getSessionOperator(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionOperatorName")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionOperatorName()");
		    	output = CApiXStudioWeb_Campaign.getSessionOperatorName(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionFollowers")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionFollowers()");
		    	output = CApiXStudioWeb_Campaign.getSessionFollowers(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionSut")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionSut()");
		    	output = CApiXStudioWeb_Campaign.getSessionSut(session, request.getParameter("sessionId"));
		    	
		    
		    } else if (command.equalsIgnoreCase("getSessionSutDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionSutDetails()");
		    	output = CApiXStudioWeb_Campaign.getSessionSutDetails(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionBugs")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionBugs()");
		    	output = CApiXStudioWeb_Campaign.getSessionBugs(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getTestExecutionsBugs")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTestExecutionsBugs()");
		    	output = CApiXStudioWeb_Campaign.getTestExecutionsBugs(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("instanceIndex"), request.getParameter("testIds"));
		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionAttachments()");
		        output = CApiXStudioWeb_Campaign.getSessionAttachments(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("getSessionAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionAgents()");
		        output = CApiXStudioWeb_Campaign.getSessionAgents(session, request.getParameter("sessionId"), request.getParameter("agentId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSutCometTestsPrioritization")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSutCometTestsPrioritization()");
		        output = CApiXStudioWeb_Campaign.getSutCometTestsPrioritization(session, request.getParameter("sutId"), request.getParameter("campaignId"));
			    


		    } else if (command.equalsIgnoreCase("getSessionTestConsolidationStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestConsolidationStatus()");
		        output = CApiXStudioWeb_Campaign.getSessionTestConsolidationStatus(session, request.getParameter("sessionId"), request.getParameter("instanceId"), request.getParameter("testId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcaseExecutionId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestConsolidationStatus()");
		        
		        if (request.getParameter("instanceId") != null)
		        	output = CApiXStudioWeb_Campaign.getSessionTestcaseExecutionId(session, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("instanceId"));
		        
		        else if (request.getParameter("instanceIndex") != null)
		        	output = CApiXStudioWeb_Campaign.getSessionTestcaseExecutionId(session, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
		        
		        else
		        	output = CRestUtils.formatError("invalid parameters");
			    


		    } else if (command.equalsIgnoreCase("getSessionTestResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestResults()");
		        output = CApiXStudioWeb_Campaign.getSessionTestResults(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestcaseResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseResults()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseResults(session, request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestsAndTestcasesResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestsAndTestcasesResults()");
		        output = CApiXStudioWeb_Campaign.getSessionTestsAndTestcasesResults(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestcasesResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcasesResults()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcasesResults(session, request.getParameter("sessionId"));
			    


		    } else if (command.equalsIgnoreCase("getSessionTestcaseMessages")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseMessages()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseMessages(session, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestcaseAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseAttachments()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseAttachments(session, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcasesWithStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcasesWithStatus()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcasesWithStatus(session, request.getParameter("sessionId"), request.getParameter("categoryId"), request.getParameter("instanceId"), request.getParameter("status"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionMonitoring")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionMonitoring()");
		        output = CApiXStudioWeb_Campaign.getSessionMonitoring(session, request.getParameter("sessionId"), request.getParameter("agentId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionMonitorId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionMonitorId()");
		        output = CApiXStudioWeb_Campaign.getSessionMonitorId(session, request.getParameter("sessionId"), request.getParameter("monitoredServerId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionMonitoredServers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionMonitoredServers()");
		        output = CApiXStudioWeb_Campaign.getSessionMonitoredServers(session, request.getParameter("sessionId"));
			    
			    
		        
		    } else if (command.equalsIgnoreCase("getSessionState")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionState()");
		    	output = CApiXStudioWeb_Campaign.getSessionState(session, request.getParameter("sessionId"));

		    } else if (command.equalsIgnoreCase("getSessionAgentState")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionAgentState()");
		    	output = CApiXStudioWeb_Campaign.getSessionAgentState(session, request.getParameter("sessionId"), request.getParameter("agentId"));
		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionName")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionName()");
		    	output = CApiXStudioWeb_Campaign.getSessionName(session, request.getParameter("sessionId"));
		    	

		    } else if (command.equalsIgnoreCase("getSessionExecutionOptions")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionExecutionOptions()");
		    	output = CApiXStudioWeb_Campaign.getSessionExecutionOptions(session, request.getParameter("sessionId"));
		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionInstances")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionInstances()");
		    	output = CApiXStudioWeb_Campaign.getSessionInstances(session, request.getParameter("sessionId"), request.getParameter("agentId"));
		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionTestcaseExecutionAttachments")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseExecutionAttachments()");
		    	output = CApiXStudioWeb_Campaign.getSessionTestcaseExecutionAttachments(session, request.getParameter("sessionId"), request.getParameter("testcaseIds"));
		    	

		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionAssets")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionAssets()");
		    	output = CApiXStudioWeb_Campaign.getSessionAssets(session, request.getParameter("sessionId"));
		    	

		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionOperatorEmail")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionToEmail()");
		    	output = CApiXStudioWeb_Campaign.getSessionOperatorEmail(session, request.getParameter("sessionId"));
		    	
		    
		    } else if (command.equalsIgnoreCase("getSessionFollowerEmails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionFollowerEmails()");
		    	output = CApiXStudioWeb_Campaign.getSessionFollowerEmails(session, request.getParameter("sessionId"));
		    	
		    	
		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getSessionResultsTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionResultsTests()");
			    output = CApiXStudioWeb_Campaign.getSessionResultsTests(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionResultsTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionResultsTestcases()");
			    output = CApiXStudioWeb_Campaign.getSessionResultsTestcases(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionResultsSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionResultsSteps()");
			    output = CApiXStudioWeb_Campaign.getSessionResultsTestcases(session, request.getParameter("sessionId"));
			    
		    	
		    } else if (command.equalsIgnoreCase("getSessionResultsStatistics")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionResultsStatistics()");
			    output = CApiXStudioWeb_Campaign.getSessionResultsStatistics(session, request.getParameter("sessionId"));
			    

			    
			    
			    
			// to show the content of each selected selection
		    } else if (command.equalsIgnoreCase("getSessionConfigurations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionConfigurations()");
		        output = CApiXStudioWeb_Campaign.getSessionConfigurations(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getConfigurationForms")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getConfigurationForm()");
		        output = CApiXStudioWeb_Campaign.getConfigurationForms(session, request.getParameter("categoryId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getConfigurationContent")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getConfigurationContent()");
		        output = CApiXStudioWeb_Campaign.getConfigurationContent(session, request.getParameter("confId"));
			    
			    

			// to populate the configuration comboboxes
		    } else if (command.equalsIgnoreCase("getConfigurationsForJarName")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getConfigurationsForJarName()");
		        output = CApiXStudioWeb_Campaign.getConfigurationsForJarName(session, request.getParameter("jarName"));
			    
			    
		    } else if (command.equalsIgnoreCase("getConfigurationsForCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getConfigurationsForCategory()");
		        output = CApiXStudioWeb_Campaign.getConfigurationsForCategory(session, request.getParameter("categoryId"));
			    
			    

/*			    
		    } else if (command.equalsIgnoreCase("getSessionSelectedConfiguration")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionSelectedConfiguration()");
		        output = CApiXStudioWeb_Campaign.getSessionSelectedConfiguration(session, request.getParameter("sessionId"), request.getParameter("categoryId"));
			    
*/

		    } else if (command.equalsIgnoreCase("getWebClientAgentId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getWebClientAgentId()");
		        output = CApiXStudioWeb_Campaign.getWebClientAgentId(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getWebClientAgent")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getWebClientAgent()");
		        output = CApiXStudioWeb_Campaign.getWebClientAgent(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionInstanceId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionInstanceId()");
		        output = CApiXStudioWeb_Campaign.getSessionInstanceId(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    

		    } else if (command.equalsIgnoreCase("getSessionWebClientInstanceId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionWebClientInstanceId()");
		        output = CApiXStudioWeb_Campaign.getSessionWebClientInstanceId(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestsAndTestcasesForExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestsAndTestcasesForExecution()");
		        output = CApiXStudioWeb_Campaign.getSessionTestsAndTestcasesForExecution(session, request.getParameter("sessionId"), request.getParameter("includeMessages"));

		    } else if (command.equalsIgnoreCase("getSessionTestsForExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestsForExecution()");
		        output = CApiXStudioWeb_Campaign.getSessionTestsForExecution(session, request.getParameter("sessionId"), request.getParameter("includeMessages"));
			    

			/* NOT USED ANYMORE
		    } else if (command.equalsIgnoreCase("getSessionTestcaseForExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseForExecution()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseForExecution(session, request.getParameter("sessionId"), request.getParameter("testcaseId"));
			    
			*/
			    

		    } else if (command.equalsIgnoreCase("getSessionTestDataForBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestDataForBug()");
		        output = CApiXStudioWeb_Campaign.getSessionTestDataForBug(session, request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("externalFormat"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestcaseDataForBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseDataForBug()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseDataForBug(session, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("externalFormat"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("_getTestsFromSession")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getTestsFromSession()");
		        output = CApiXStudioWeb_Campaign._getTestsFromSession(session, request.getParameter("sessionId"), request.getParameter("isScheduled"));
			    

		    } else if (command.equalsIgnoreCase("_getTestsFromSessionAndCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getTestsFromSessionAndCategory()");
		        output = CApiXStudioWeb_Campaign._getTestsFromSessionAndCategory(session, request.getParameter("sessionId"), request.getParameter("categoryId"), request.getParameter("isScheduled"));
			    

			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("_getSessionTestcaseReadyForFlagsTestsAndCategories")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcaseReadyForFlagsTestsAndCategories()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseReadyForFlagsTestsAndCategories(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestAssetRuleAndAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getCampaignSessionTestsAssetRuleAndAssets()");
		        output = CApiXStudioWeb_Campaign.getSessionTestAssetRuleAndAssets(session, request.getParameter("sessionId"));
			    

			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getInstanceTestcasesStats")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getInstanceTestcasesStats()");
		        output = CApiXStudioWeb_Campaign.getInstanceTestcasesStats(session, request.getParameter("sessionId"), request.getParameter("categoryIds"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    

			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestPrerequisitesAndDescriptions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestPrerequisitesAndDescriptions()");
		        output = CApiXStudioWeb_Campaign.getSessionTestPrerequisitesAndDescriptions(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestCanonicalPathsAndAdditionalInfos")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestCanonicalPathsAndAdditionalInfos()");
		        output = CApiXStudioWeb_Campaign.getSessionTestCanonicalPathsAndAdditionalInfos(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestAttachments()");
		        output = CApiXStudioWeb_Campaign.getSessionTestAttachments(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("getSessionTestAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestAssets()");
		        output = CApiXStudioWeb_Campaign.getSessionTestAssets(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestBugsInOtherSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestBugsInOtherSessions()");
		        output = CApiXStudioWeb_Campaign.getSessionTestBugsInOtherSessions(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestBugsInCurrentSession")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestBugsInCurrentSession()");
		        output = CApiXStudioWeb_Campaign.getSessionTestBugsInCurrentSession(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("sessionId"));
			    
			    
			   
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("_getSessionTestsBooleanCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestsBooleanCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestsBooleanCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestsIntegerCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestsIntegerCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestsIntegerCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestsStringCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestsStringCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestsStringCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestsHtmlCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestsHtmlCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestsHtmlCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestsStringChoiceCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestsStringChoiceCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestsStringChoiceCustomFields(session, request.getParameter("sessionId"));
			    

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcaseDescriptionsAndStepsAndEditorTypes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseDescriptionsAndStepsAndEditorTypes()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseDescriptionsAndStepsAndEditorTypes(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcaseSteps")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseSteps()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseSteps(session, request.getParameter("sessionId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcaseAdditionalInfos")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseAdditionalInfos()");
		        output = CApiXStudioWeb_Campaign.getSessionTestcaseAdditionalInfos(session, request.getParameter("sessionId"));
			    
		    
		    } else if (command.equalsIgnoreCase("_getSessionTestcaseAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcaseAttachments()");
		        output = CApiXStudioWeb_Campaign._getSessionTestcaseAttachments(session, request.getParameter("sessionId"));
			    

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("_getSessionTestcasesBooleanCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcasesBooleanCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestcasesBooleanCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestcasesIntegerCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcasesIntegerCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestcasesIntegerCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestcasesStringCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcasesStringCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestcasesStringCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestcasesHtmlCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcasesHtmlCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestcasesHtmlCustomFields(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("_getSessionTestcasesStringChoiceCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - _getSessionTestcasesStringChoiceCustomFields()");
			    output = CApiXStudioWeb_Campaign._getSessionTestcasesStringChoiceCustomFields(session, request.getParameter("sessionId"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestAttributeValues")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestAttributeValues()");
			    output = CApiXStudioWeb_Campaign.getSessionTestAttributeValues(session, request.getParameter("sessionId"));
			    
			    
	    	} else if (command.equalsIgnoreCase("getScheduledSessionTestAttributeValues")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduledSessionTestAttributeValues()");
			    output = CApiXStudioWeb_Campaign.getScheduledSessionTestAttributeValues(session, request.getParameter("sessionId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getSessionTestcaseParamValues")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSessionTestcaseParamValues()");
			    output = CApiXStudioWeb_Campaign.getSessionTestcaseParamValues(session, request.getParameter("sessionId"));
			    
			    
	    	} else if (command.equalsIgnoreCase("getScheduledSessionTestcaseParamValues")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getScheduledSessionTestcaseParamValues()");
			    output = CApiXStudioWeb_Campaign.getScheduledSessionTestcaseParamValues(session, request.getParameter("sessionId"));
			    
			    
			    
			    
			    
			    
			    
	    	} else if (command.equalsIgnoreCase("isInstanceCategoryReady")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - isInstanceCategoryReady()");
			    output = CApiXStudioWeb_Campaign.isInstanceCategoryReady(session, request.getParameter("sessionId"), request.getParameter("categoryId"), request.getParameter("instanceId"));
			    
			    
	    	} else if (command.equalsIgnoreCase("areAllInstanceCategoryReady")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - areAllInstanceCategoryReady()");
			    output = CApiXStudioWeb_Campaign.areAllInstanceCategoryReady(session, request.getParameter("sessionId"), request.getParameter("categoryId"), request.getParameter("testId"), request.getParameter("totalNbInstances"));
			    
			    
			    
			    
			    
			    
			    
			    
			    
			    

			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Bugs
			// ----------------------------------------------------------------------------------------------------------------------------------------
			
		    } else if (command.equalsIgnoreCase("getBugsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugsTree()");
			    
		        String requestId = request.getHeader("X-Request-ID");
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  requestId = " + requestId);
		        CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  idempotencyStore = " + idempotencyStore);

		        if (requestId == null || requestId.isEmpty()) {
		            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Request-ID header is required for getBugsTree()");
		            return;
		        }

		        synchronized (idempotencyStore) {
		            if (idempotencyStore.putIfAbsent(requestId, CCalendarUtils.getCurrentTimeMillis()) == null) { // the key (requestId) is not present in the idempotency store
		                // This is a new request, process it
				    	output = CApiXStudioWeb_Defect.getBugsTree(session, request.getParameter("treeFilter"), request.getParameter("onlyIntegrated"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));

		            } else { // the key was already present
		            	output = CRestUtils.formatDuplicateRequestId(requestId);
		            	cleanupIdempotencyStore();
		            }
		        }
		    	
		    } else if (command.equalsIgnoreCase("getBugFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFreezeStatus()");
		    	output = CApiXStudioWeb_Defect.getBugFreezeStatus(session, request.getParameter("bugId"));
		    	

		    } else if (command.equalsIgnoreCase("getBugForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugForm()");
			    output = CApiXStudioWeb_Defect.getBugForm(session, request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("testcaseId")/*, request.getParameter("instanceId")*/);
			    
			    
		    } else if (command.equalsIgnoreCase("getGenericBugForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getGenericBugForm()");
			    output = CApiXStudioWeb_Defect.getGenericBugForm(session);
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getExternalBugConnectorForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExternalBugConnectorForm()");
			    output = CApiXStudioWeb_Defect.getExternalBugConnectorForm(session);
			    

		    } else if (command.equalsIgnoreCase("getExternalBugProjectForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExternalBugProjectForm()");
			    output = CApiXStudioWeb_Defect.getExternalBugProjectForm(session, request.getParameter("connectorIndex"));
			    

		    } else if (command.equalsIgnoreCase("getExternalBugForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getExternalBugForm()");
			    output = CApiXStudioWeb_Defect.getExternalBugForm(session, request.getParameter("connectorIndex"), request.getParameter("projectName"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugDetails()");
			    output = CApiXStudioWeb_Defect.getBugDetails(session, request.getParameter("connectorIndex"), request.getParameter("bugId"), request.getParameter("frozen"));
		    	
	
		    } else if (command.equalsIgnoreCase("getBugSummary")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugSummary()");
			    output = CApiXStudioWeb_Defect.getBugSummary(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
		    	
	
		    } else if (command.equalsIgnoreCase("getBugDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugDetailsRevision()");
			    output = CApiXStudioWeb_Defect.getBugDetailsRevision(session, request.getParameter("bugId"), request.getParameter("revision"));
			    
		    	
	
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getBugAssignedTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugAssignedTo()");
			    output = CApiXStudioWeb_Defect.getBugAssignedTo(session, request.getParameter("bugId"));
			    
		    	
		    } else if (command.equalsIgnoreCase("getBugFoundIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFoundIn()");
			    output = CApiXStudioWeb_Defect.getBugFoundIn(session, request.getParameter("bugId")); // only for integrated
			    
		    	
		    } else if (command.equalsIgnoreCase("getBugFixedIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFixedIn()");
			    output = CApiXStudioWeb_Defect.getBugFixedIn(session, request.getParameter("bugId")); // only for integrated
			    
		    	
		    } else if (command.equalsIgnoreCase("getBugFollowers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFollowers()");
			    output = CApiXStudioWeb_Defect.getBugFollowers(session, request.getParameter("bugId"));
			    
			    
			    
			    
			    

		    } else if (command.equalsIgnoreCase("getBugFolderFullTraceability")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFolderFullTraceability()");

		        if (request.getParameter("connectorIndex") == null) { // used when a connector or a folder is selected
		        	//System.out.println("- - - ->> GET MATRIX ON THE ROOT FOLDER");
		        	output = CApiXStudioWeb_Common.getBugRootFolderFullTraceability(session);
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	//System.out.println("- - - - folderId = " + strFolderId);
		        	if (strFolderId != null) {
		        		ArrayList<String> alreadyExistingLinks = new ArrayList<String>(); // keep traces of all existing links (i.e. reqs-test coming from different bug connectors)
		        		
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET MATRIX ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Common.getBugConnectorFullTraceability(session, request.getParameter("connectorIndex"), alreadyExistingLinks);
		        		} else {
		        			//System.out.println("- - - ->> GET MATRIX ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
				        	output = CApiXStudioWeb_Common.getBugFolderFullTraceability(session, request.getParameter("connectorIndex"), request.getParameter("folderId"), alreadyExistingLinks);
		        		}
		        	}
		        }

		    } else if (command.equalsIgnoreCase("getBugFullTraceability")) { // can be 1 or N requirements
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFullTraceability()");
		        ArrayList<String> alreadyExistingLinks = new ArrayList<String>(); // keep traces of all existing links (i.e. reqs-test coming from different bug connectors)
			    output = CApiXStudioWeb_Common.getBugFullTraceability(session, request.getParameter("connectorIndex"), request.getParameter("bugIds"), alreadyExistingLinks);

			    
			    
			    
			    
			    
		    	
		    } else if (command.equalsIgnoreCase("getBugLinkedSutsFoundIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedSutsFoundIn()");
			    output = CApiXStudioWeb_Defect.getBugLinkedSutsFoundIn(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugLinkedRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedRequirements()");
			    output = CApiXStudioWeb_Defect.getBugLinkedRequirements(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugLinkedSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedSpecifications()");
			    output = CApiXStudioWeb_Defect.getBugLinkedSpecifications(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugLinkedTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedTests()");
			    output = CApiXStudioWeb_Defect.getBugLinkedTests(session, request.getParameter("connectorIndex"), request.getParameter("bugIds"));

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getBugResultsTreeview")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugResultsTreeview()");
			    output = CApiXStudioWeb_Defect.getBugResultsTreeview(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getBugLinkedSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedSessions()");
			    output = CApiXStudioWeb_Defect.getBugLinkedSessions(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    

		    } else if (command.equalsIgnoreCase("getBugLinkedExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugLinkedExploratorySessions()");
			    output = CApiXStudioWeb_Defect.getBugLinkedExploratorySessions(session, request.getParameter("connectorIndex"), request.getParameter("bugId"));
			    
			    

			    
			    
		    } else if (command.equalsIgnoreCase("getBugsProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugsProgress()");
			    output = CApiXStudioWeb_Defect.getBugsProgress(session, request.getParameter("connectorIndex"), request.getParameter("folderId"));
		    
		    } else if (command.equalsIgnoreCase("getAssignedToBugsProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssignedToBugsProgress()");
			    output = CApiXStudioWeb_Defect.getAssignedToBugsProgress(session, request.getParameter("connectorIndex"), request.getParameter("folderId"), request.getParameter("username"));
			    
		    } else if (command.equalsIgnoreCase("getReportedByBugsProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReportedByBugsProgress()");
			    output = CApiXStudioWeb_Defect.getReportedByBugsProgress(session, request.getParameter("connectorIndex"), request.getParameter("folderId"), request.getParameter("username"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("getBugCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugCustomFields()");
		        output = CApiXStudioWeb_Defect.getBugCustomFields(session, request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugAttachments()");
		        output = CApiXStudioWeb_Defect.getBugAttachments(session, request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugInheritedAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugInheritedAttachments()");
		        output = CApiXStudioWeb_Defect.getBugInheritedAttachments(session, request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugRevisions()");
		        output = CApiXStudioWeb_Defect.getBugRevisions(session, request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getBugChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugChanges()");
		        output = CApiXStudioWeb_Defect.getBugChanges(session, request.getParameter("bugId"));
			    
			    
		        
		        
		        
		        
		    /*
		    NOT NEEDED, THE WEB CLIENT DOES CALCULATE THE GRAPH FROM THE TREE
		    
		    } else if (command.equalsIgnoreCase("getBugFolderSeverityPerStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugChanges()");
		        output = CApiXStudioWeb_Defect.getBugFolderSeverityPerStatus(session, request.getParameter("folderId"));
		    } else if (command.equalsIgnoreCase("getBugFolderPriorityPerStatus")) {
		    } else if (command.equalsIgnoreCase("getBugFolderStatusPerAssignee")) {
		    } else if (command.equalsIgnoreCase("getBugFolderSeverityPerAssignee")) {
		    } else if (command.equalsIgnoreCase("getBugFolderPriorityPerAssignee")) {
		    */

		    } else if (command.equalsIgnoreCase("getBugFolderStatisticsPerActiveBugAge")) { // folderId can be root or a normal folder
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFolderStatisticsPerActiveBugAge()");
		        if (request.getParameter("connectorIndex") == null) { // root
		        	output = CApiXStudioWeb_Defect.getBugRootFolderStatisticsPerActiveBugAge(session);
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	if (strFolderId != null) {
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET MATRIX ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Defect.getBugConnectorStatisticsPerActiveBugAge(session, request.getParameter("connectorIndex"));
		        		} else {
		        			//System.out.println("- - - ->> GET MATRIX ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
				        	output = CApiXStudioWeb_Defect.getBugFolderStatisticsPerActiveBugAge(session, request.getParameter("connectorIndex"), request.getParameter("folderId"));
		        		}
		        	}
		        }
		        
		        
		        
		        
		        
		        
		        
		        

		    } else if (command.equalsIgnoreCase("updateDefectHistory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - updateDefectHistory()");
		        if (request.getParameter("connectorIndex") == null) { // used when a connector or a folder is selected
		        	output = CApiXStudioWeb_Defect.updateDefectRootFolderHistory(session);
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	if (strFolderId != null) {
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET HISTORY ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Defect.updateDefectConnectorHistory(session, request.getParameter("connectorIndex"));
		        		} else {
		        			//System.out.println("- - - ->> GET HISTORY ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
				        	output = CApiXStudioWeb_Defect.updateDefectFolderHistory(session, request.getParameter("connectorIndex"), request.getParameter("folderId"));
		        		}
		        	}
		        }
		        
		    } else if (command.equalsIgnoreCase("getBugFolderProgress")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getBugFolderProgress()");
			    
		        if (request.getParameter("connectorIndex") == null) { // used when a connector or a folder is selected
		        	output = CApiXStudioWeb_Defect.getBugRootFolderProgress(session, request.getParameter("startDate"), request.getParameter("stopDate"));
		        } else {
		        	String strFolderId = request.getParameter("folderId");
		        	if (strFolderId != null) {
		        		if (strFolderId.startsWith("-")) { // negative id = virtual connector folder
		        			//System.out.println("- - - ->> GET HISTORY ON A CONNECTOR / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Defect.getBugConnectorProgress(session, request.getParameter("connectorIndex"), request.getParameter("startDate"), request.getParameter("stopDate"));
		        		} else {
		        			//System.out.println("- - - ->> GET HISTORY ON A FOLDER OR PROJECT / connector index = " + request.getParameter("connectorIndex"));
		        			output = CApiXStudioWeb_Defect.getBugFolderProgress(session, request.getParameter("connectorIndex"), request.getParameter("folderId"), request.getParameter("startDate"), request.getParameter("stopDate"));
		        		}
		        	}
		        }


			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Users
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getUsersTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUsersTree()");
		    	output = CApiXStudioWeb_User.getUsersTree(session, request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
				
		    } else if (command.equalsIgnoreCase("getUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUsers()");
			    output = CApiXStudioWeb_User.getUsers(session);
			    
		    } else if (command.equalsIgnoreCase("getEnabledUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getEnabledUsers()");
			    output = CApiXStudioWeb_User.getEnabledUsers(session);
			    
		    } else if (command.equalsIgnoreCase("getAllUsersFromLDAP")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAllUsersFromLDAP()");
			    output = CApiXStudioWeb_User.getAllUsersFromLDAP(session);
			    
		    } else if (command.equalsIgnoreCase("getUserForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserForm()");
			    output = CApiXStudioWeb_User.getUserForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getUserDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserDetails()");
			    output = CApiXStudioWeb_User.getUserDetails(session, request.getParameter("userId"));
			    

		    } else if (command.equalsIgnoreCase("getUserSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserSummary()");
			    output = CApiXStudioWeb_User.getUserSummary(session, request.getParameter("userId"));
			    

			    
		    } else if (command.equalsIgnoreCase("getUserTeams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserTeams()");
			    output = CApiXStudioWeb_User.getUserUserGroups(session, request.getParameter("userId"));
			    

		    } else if (command.equalsIgnoreCase("getUserProfile")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserProfile()");
			    output = CApiXStudioWeb_User.getUserProfile(session, request.getParameter("userId"), request.getParameter("teamId"));
			    

		    } else if (command.equalsIgnoreCase("getUserRoles")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getUserRoles()");
			    output = CApiXStudioWeb_User.getUserRoles(session, request.getParameter("userId"));
			    


			    
		    } else if (command.equalsIgnoreCase("getProfiles")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getProfiles()");
			    output = CApiXStudioWeb_User.getProfiles(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getProfile")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getProfiles()");
			    output = CApiXStudioWeb_User.getProfile(session, request.getParameter("profileId"));
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("getTeamForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamForm()");
			    output = CApiXStudioWeb_User.getUserGroupForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getTeamDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamDetails()");
			    output = CApiXStudioWeb_User.getUserGroupDetails(session, request.getParameter("teamId"));
			    

		    } else if (command.equalsIgnoreCase("getTeamSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamSummary()");
			    output = CApiXStudioWeb_User.getUserGroupSummary(session, request.getParameter("teamId"));
			    


		    } else if (command.equalsIgnoreCase("getTeamUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamUsers()");
			    output = CApiXStudioWeb_User.getUserGroupUsers(session, request.getParameter("teamId"));
			    

		    } else if (command.equalsIgnoreCase("getFilteredTeams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getFilteredTeams()");
			    output = CApiXStudioWeb_User.getFilteredTeams(session);
			    

		    } else if (command.equalsIgnoreCase("getTeamAccessRights")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamAccessRights()");
			    output = CApiXStudioWeb_User.getUserGroupAccessRights(session, request.getParameter("teamId"));
			    

		    } else if (command.equalsIgnoreCase("getTeamAccessRightsRequirementConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamAccessRightsRequirementConnector()");
			    output = CApiXStudioWeb_User.getUserGroupAccessRightsRequirementConnector(session, request.getParameter("teamId"), request.getParameter("connectorIndex"));
			    

		    } else if (command.equalsIgnoreCase("getTeamAccessRightsBugConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTeamAccessRightsBugConnector()");
			    output = CApiXStudioWeb_User.getUserGroupAccessRightsBugConnector(session, request.getParameter("teamId"), request.getParameter("connectorIndex"));
			    
			    
		    } else if (command.equalsIgnoreCase("downloadUserPhoto")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadUserPhoto()");
			    output = CApiXStudioWeb_User.downloadUserPhoto(session, response, request.getParameter("userId"));
			    
			    



			// Used for community license verification (i.e. in XAgent)
				
		    } else if (command.equalsIgnoreCase("getNbEnabledUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNbEnabledUsers()");
			    output = CApiXStudioWeb_User.getNbEnabledUsers(session);
			    

		    } else if (command.equalsIgnoreCase("getNbUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getNbUsers()");
			    output = CApiXStudioWeb_User.getNbUsers(session);
			    

			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Assets
			// ----------------------------------------------------------------------------------------------------------------------------------------
		    
		    } else if (command.equalsIgnoreCase("getAssetSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetSummary()");
			    output = CApiXStudioWeb_Asset.getAssetSummary(session, request.getParameter("assetId"));
			    

		    } else if (command.equalsIgnoreCase("getAssetsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetsTree()");
		    	output = CApiXStudioWeb_Asset.getAssetsTree(session, request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));

		    } else if (command.equalsIgnoreCase("getAssetTypes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetTypes()");
		        output = CApiXStudioWeb_Asset.getAssetTypes(session);
			    

		    } else if (command.equalsIgnoreCase("getAssetForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetForm()");
			    output = CApiXStudioWeb_Asset.getAssetForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getAssetDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetDetails()");
			    output = CApiXStudioWeb_Asset.getAssetDetails(session, request.getParameter("assetId")/*, request.getParameter("frozen")*/); // no freeze or signature on assets
			    

		    } else if (command.equalsIgnoreCase("getAssetDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetDetailsRevision()");
			    output = CApiXStudioWeb_Asset.getAssetDetailsRevision(session, request.getParameter("assetId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getAssetRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetRevisions()");
		        output = CApiXStudioWeb_Asset.getAssetRevisions(session, request.getParameter("assetId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getAssetChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAssetChanges()");
		        output = CApiXStudioWeb_Asset.getAssetChanges(session, request.getParameter("assetId"));
			    

			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Reusable Testcases
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    } else if (command.equalsIgnoreCase("getReusableTestcaseForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseForm()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseForm(session);
			    
	
		    } else if (command.equalsIgnoreCase("getReusableTestcaseDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseDetails()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseDetails(session, request.getParameter("reusableTestcaseId"));
			    

		    } else if (command.equalsIgnoreCase("getReusableTestcaseSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseSummary()");
			    output = CApiXStudioWeb_Test.getReusableTestcaseSummary(session, request.getParameter("reusableTestcaseId"));
			    
	
		    } else if (command.equalsIgnoreCase("getReusableTestcaseDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseDetailsRevision()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseDetailsRevision(session, request.getParameter("reusableTestcaseId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getReusableTestcaseProcedure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseProcedure()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseProcedure(session, request.getParameter("reusableTestcaseId"));
			    
	
		    } else if (command.equalsIgnoreCase("getReusableTestcaseProcedureRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseProcedureRevision()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseProcedureRevision(session, request.getParameter("reusableTestcaseId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getReusableTestcaseTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseTests()");
			    output = CApiXStudioWeb_Asset.getReusableTestcaseTests(session, request.getParameter("reusableTestcaseId"));
			    

		    } else if (command.equalsIgnoreCase("getReusableTestcaseRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseRevisions()");
		        output = CApiXStudioWeb_Asset.getReusableTestcaseRevisions(session, request.getParameter("reusableTestcaseId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getReusableTestcaseChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getReusableTestcaseChanges()");
		        output = CApiXStudioWeb_Asset.getReusableTestcaseChanges(session, request.getParameter("reusableTestcaseId"));
			    

			    
			    
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Agents
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getAgentsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentsTree()");
		    	output = CApiXStudioWeb_Agent.getAgentsTree(session, request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));

/*			    
		    } else if (command.equalsIgnoreCase("getAgentForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentForm()");
			    output = CApiXStudioWeb_Agent.getAgentForm(session);
			    
*/
		    } else if (command.equalsIgnoreCase("getAgentDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentDetails()");
			    output = CApiXStudioWeb_Agent.getAgentDetails(session, request.getParameter("agentId"));
			    

		    } else if (command.equalsIgnoreCase("getAgentId")) { 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentId()");
		        output = CApiXStudioWeb_Agent.getAgentId(session, request.getParameter("name"), request.getParameter("ipAddress"));
			    

		    } else if (command.equalsIgnoreCase("getAgentName")) { 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentName()");
		        output = CApiXStudioWeb_Agent.getAgentName(session, request.getParameter("agentId"));
			    


		    } else if (command.equalsIgnoreCase("getAgentStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentStatus()");
			    output = CApiXStudioWeb_Agent.getAgentStatus(session, request.getParameter("agentId"));
			    

		    } else if (command.equalsIgnoreCase("getAgentSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getAgentSummary()");
			    output = CApiXStudioWeb_Agent.getAgentSummary(session, request.getParameter("agentId"));
			    


			// ----------------------------------------------------------------------------------------------------------------------------------------
			// SQL reports
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getSqlReportsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportsTree()");
		    	output = CApiXStudioWeb_SqlReport.getSqlReportsTree(session, request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
			    
		    } else if (command.equalsIgnoreCase("getSqlReportForm")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportForm()");
			    output = CApiXStudioWeb_SqlReport.getSqlReportForm(session);
			    
			    
		    } else if (command.equalsIgnoreCase("getSqlReportDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportDetails()");
			    output = CApiXStudioWeb_SqlReport.getSqlReportDetails(session, request.getParameter("sqlReportId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getSqlReportSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportSummary()");
			    output = CApiXStudioWeb_SqlReport.getSqlReportSummary(session, request.getParameter("sqlReportId"));
			    

		    } else if (command.equalsIgnoreCase("getSqlReportRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportRevisions()");
		        output = CApiXStudioWeb_SqlReport.getSqlReportRevisions(session, request.getParameter("sqlReportId"));
			    

		    } else if (command.equalsIgnoreCase("getSqlReportDetailsRevision")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportDetailsRevision()");
			    output = CApiXStudioWeb_SqlReport.getSqlReportDetailsRevision(session, request.getParameter("sqlReportId"), request.getParameter("revision"));
			    

		    } else if (command.equalsIgnoreCase("getSqlReportChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportChanges()");
		        output = CApiXStudioWeb_SqlReport.getSqlReportChanges(session, request.getParameter("sqlReportId"));
			    

		    } else if (command.equalsIgnoreCase("getSqlReportOutput")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getSqlReportOuput()");
		        output = CApiXStudioWeb_SqlReport.getSqlReportOutput(session, request.getParameter("sqlReportId"));
			    

			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Documents
			// ----------------------------------------------------------------------------------------------------------------------------------------
			    
		    } else if (command.equalsIgnoreCase("getDocumentsTree")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDocumentsTree()");
		    	output = CApiXStudioWeb_Document.getDocumentsTree(session, request.getParameter("treeFilter"), request.getParameter("cache"), request.getParameter("clientCacheMillisUTC"));
	
		    } else if (command.equalsIgnoreCase("getDocumentFreezeStatus")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDocumentFreezeStatus()");
		    	output = CApiXStudioWeb_Document.getDocumentFreezeStatus(session, request.getParameter("documentId"));
	
		    } else if (command.equalsIgnoreCase("getDocumentDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDocumentDetails()");
			    output = CApiXStudioWeb_Document.getDocumentDetails(session, request.getParameter("documentId"), request.getParameter("frozen"));
			    
		    } else if (command.equalsIgnoreCase("getDocumentRevisions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDocumentRevisions()");
			    output = CApiXStudioWeb_Document.getDocumentRevisions(session, request.getParameter("documentId"));
			    
		    } else if (command.equalsIgnoreCase("getDocumentChanges")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getDocumentChanges()");
		        output = CApiXStudioWeb_Document.getDocumentChanges(session, request.getParameter("documentId"));

		    } else if (command.equalsIgnoreCase("downloadDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadDocument()");
			    output = CApiXStudioWeb_Common.downloadDocument(session, response, request.getParameter("documentId"));
	
		    } else if (command.equalsIgnoreCase("downloadDocumentRevision")) { // download a specific revision of a document
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - downloadDocumentRevision()");
			    output = CApiXStudioWeb_Common.downloadDocumentRevision(session, response, request.getParameter("documentId"), request.getParameter("revision"));
			    
			    
			    
			    
			    
			    
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Tasks
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    } else if (command.equalsIgnoreCase("getTaskSummary")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getTaskSummary()");
			    output = CApiXStudioWeb_Project.getTaskSummary(session, request.getParameter("taskId"));
			    
	
			    
			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Gherkin
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    	
		    } else if (command.equalsIgnoreCase("getStatements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatements()");
			    output = CApiXStudioWeb_Gherkin.getStatements(session, request.getParameter("statementType")); // when we provide statementType only the basic information are returned for the editor, else this is used in the statement management panel
			    
		    	
		    } else if (command.equalsIgnoreCase("getStatementForm")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatementForm()");
		    	output = CApiXStudioWeb_Gherkin.getStatementForm(session);
		    	

		    } else if (command.equalsIgnoreCase("getStatementDetails")) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatementDetails()");
		    	output = CApiXStudioWeb_Gherkin.getStatementDetails(session, request.getParameter("statementId"));
		    	

		    } else if (command.equalsIgnoreCase("getStatementDescription")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatementDescription()");
			    output = CApiXStudioWeb_Gherkin.getStatementDescription(session, request.getParameter("statementId"));
			    

		    } else if (command.equalsIgnoreCase("getStatementHelp")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatementHelp()");
			    output = CApiXStudioWeb_Gherkin.getStatementHelp(session, request.getParameter("statementId"));
			    

		    } else if (command.equalsIgnoreCase("getStatementTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getStatementTests()");
			    output = CApiXStudioWeb_Gherkin.getStatementTests(session, request.getParameter("statementId"));
			    

			// ----------------------------------------------------------------------------------------------------------------------------------------
			// Dashboard
			// ----------------------------------------------------------------------------------------------------------------------------------------

		    } else if (command.equalsIgnoreCase("getDashboards")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getDashboards");
		    	output = CApiXStudioWeb_Dashboard.getDashboards(session);
		    	
		    	
		    	
		    	
		    	
		    // New features
		    } else if (command.equalsIgnoreCase("getNewFeatures")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - getNewFeatures()");
			    output = CApiXStudioWeb_Dashboard.getNewFeatures(session);
			    
			    
		    } else if (command.equalsIgnoreCase("haveNewFeaturesBeenNotified")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  GET - haveNewFeaturesBeenNotified()");
			    output = CApiXStudioWeb_Dashboard.haveNewFeaturesBeenNotified(session);
			    
			    
			    
			    
			// Messages
		    } else if (command.equalsIgnoreCase("getContextualMessagesIHaveNotReadYet")) { // only for the API! not used by XStudio.web as it reuse the results from getDashboards()
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getContextualMessagesIHaveNotReadYet");
		    	output = CApiXStudioWeb_Dashboard.getContextualMessagesIHaveNotReadYet(session);
		    	

		    } else if (command.equalsIgnoreCase("getDirectMessagesIHaveNotReadYet")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getDirectMessagesIHaveNotReadYet");
		    	output = CApiXStudioWeb_Dashboard.getDirectMessagesIHaveNotReadYet(session); // HERE WE EXCEPTIONNALLY GET THE CONTENT OF THE MESSAGES AS WE CAN READ THEM ONLY FROM THE DASHBOARD AND NOT BY REDIRECTION
		    	
		    	
		    } else if (command.equalsIgnoreCase("getDirectMessages")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getDirectMessages");
		    	output = CApiXStudioWeb_Dashboard.getDirectMessages(session); // HERE WE EXCEPTIONNALLY GET THE CONTENT OF THE MESSAGES AS WE CAN READ THEM ONLY FROM THE DASHBOARD AND NOT BY REDIRECTION
		    	
		    	
		    	
		    	
		    } else if (command.equalsIgnoreCase("getNumberOfDirectMessagesNotReadYet")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getNumberOfDirectMessagesNotReadYet");
		    	output = CApiXStudioWeb_Dashboard.getNumberOfDirectMessagesNotReadYet(session); 
		    	
		    	
		    } else if (command.equalsIgnoreCase("getNumberOfContextualMessagesNotReadYet")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getNumberOfContextualMessagesNotReadYet");
		    	output = CApiXStudioWeb_Dashboard.getNumberOfContextualMessagesNotReadYet(session); 
		    	
		    	
		    } else if (command.equalsIgnoreCase("getNumberOfMessagesNotReadYet")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getNumberOfDirectMessagesNotReadYet");
		    	output = CApiXStudioWeb_Dashboard.getNumberOfMessagesNotReadYet(session, request.getParameter("userId")); 
		    	


		    	
		    // Tests
		    } else if (command.equalsIgnoreCase("getTestsIMustAuthor")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getTestsIMustAuthor");
		    	output = CApiXStudioWeb_Dashboard.getTestsIMustXXX(session, IStudioTreeStatements.GET_ALL_TEST_IMUSTAUTHOR_API);
		    	
		    	
		    } else if (command.equalsIgnoreCase("getTestsIMustAutomate")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getTestsIMustAutomate");
		    	output = CApiXStudioWeb_Dashboard.getTestsIMustXXX(session, IStudioTreeStatements.GET_ALL_TEST_IMUSTDEVELOP_API);
		    	

		    	
		    // Sessions
		    } else if (command.equalsIgnoreCase("getSessionsIMustExecute")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getSessionsIMustExecute");
		    	output = CApiXStudioWeb_Dashboard.getSessionsIMustExecute(session);
		    	
		    	
		    	
		    // Bugs
		    } else if (command.equalsIgnoreCase("getBugsIMustFix")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getBugsIMustFix");
		    	output = CApiXStudioWeb_Dashboard.getBugsIMustXXX(session, IStudioTreeStatements.GET_INTEGRATEDDEFECTS_IMUSTFIX_API);
		    	
		    	
		    } else if (command.equalsIgnoreCase("getBugsIMustClose")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getBugsIMustClose");
		    	output = CApiXStudioWeb_Dashboard.getBugsIMustXXX(session, IStudioTreeStatements.GET_INTEGRATEDDEFECTS_IMUSTCLOSE_API);
		    	
		    	

		    // +-------------------------------------+
		    // |          JIRA ADDON API v1          |
		    // | used with tomcat8 with old browsers |
		    // | supporting cookie/auth in iFrame    |
		    // | for v2: see POST()                  |
		    // +-------------------------------------+
/*
			// Requirements
		    } else if (command.equalsIgnoreCase("getPluginRequirementSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginRequirementSuts()");
			    output = CApiPlugin.getPluginRequirementSuts(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getPluginRequirementTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginRequirementTests()");
			    output = CApiPlugin.getPluginRequirementTests(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getPluginRequirementBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginRequirementBugs()");
			    output = CApiPlugin.getPluginRequirementBugs(session, IConstants.FIRST_CONNECTOR_INDEX, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("requirementId"));
			    

			    
		    // Bugs
		    } else if (command.equalsIgnoreCase("getPluginBugTestsSessionsCampaignsSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugTestsSessionsCampaignsSuts()");
			    output = CApiPlugin.getPluginBugTestsSessionsCampaignsSuts(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
			    
		    } else if (command.equalsIgnoreCase("getPluginBugSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugSuts()");
			    output = CApiPlugin.getPluginBugSuts(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
	
		    } else if (command.equalsIgnoreCase("getPluginBugTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugTests()");
			    output = CApiPlugin.getPluginBugTests(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
	
		    } else if (command.equalsIgnoreCase("getPluginBugSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugSessions()");
			    output = CApiPlugin.getPluginBugSessions(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
	
		    } else if (command.equalsIgnoreCase("getPluginBugCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugCampaigns()");
			    output = CApiPlugin.getPluginBugCampaigns(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
	
		    } else if (command.equalsIgnoreCase("getPluginBugRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugRequirements()");
			    output = CApiPlugin.getPluginBugRequirements(session, IConstants.FIRST_CONNECTOR_INDEX, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
			    
	
		    } else if (command.equalsIgnoreCase("getPluginBugExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - getPluginBugExploratorySessions()");
			    output = CApiPlugin.getPluginBugExploratorySessions(session, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("bugId"));
*/
	
			// +-----------------------+
			// |        IOS API        |
			// +-----------------------+
		    
		    } else if (command.equalsIgnoreCase("getAllSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getAllSuts");
			    output = CApiMobile.getAllSuts();
			    
		    } else if (command.equalsIgnoreCase("getSutCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getSutCampaigns");
			    output = CApiMobile.getSutCampaigns(request.getParameter("sutId"));
			    
		    } else if (command.equalsIgnoreCase("getSutAndCampaignCampaignSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getSutAndCampaignCampaignSessions");
			    output = CApiMobile.getSutAndCampaignCampaignSessions(request.getParameter("sutId"), request.getParameter("campaignId"));
			    
		    } else if (command.equalsIgnoreCase("getCampaignSessionResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getCampaignSessionResults");
			    output = CApiMobile.getCampaignSessionResults(request.getParameter("campaignSessionId"));
			    
		    } else if (command.equalsIgnoreCase("getCampaignSessionGraphics")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getCampaignSessionGraphics");
			    output = CApiMobile.getCampaignSessionGraphics(request.getParameter("campaignSessionId"));
		    	    
		    } else if (command.equalsIgnoreCase("getSutBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getSutBugs");
			    output = CApiMobile.getSutBugs(request.getParameter("sutId"));
		    
		    } else if (command.equalsIgnoreCase("getBugDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getBugDetails");
			    output = CApiMobile.getBugDetails(request.getParameter("bugId"));
			    
		    } else if (command.equalsIgnoreCase("getBugDetailsHtml")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getBugDetailsHtml");
			    output = CApiMobile.getBugDetailsHtml(request.getParameter("bugId"));
			    
		    } else if (command.equalsIgnoreCase("getBugGraphics")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getBugGraphics");
			    output = CApiMobile.getBugGraphics(IConstants.FIRST_CONNECTOR_INDEX);
	
		    } else if (command.equalsIgnoreCase("getAllCategories")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getAllCategories");
			    output = CApiMobile.getAllCategories();
			     
		    } else if (command.equalsIgnoreCase("getCampaignSessionDefects")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getCampaignSessionDefects");
		    	output = CApiMobile.getCampaignSessionDefects(request.getParameter("campaignSessionId"));

		    	
			// +-----------------------+
			// |       XSTUDIO TV      |
			// +-----------------------+

		    // [C]reate
		    } else if (command.equalsIgnoreCase("createChannel")) { //
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "createChannel");
		    	output = CApiDashboard.createChannel(request.getParameter("channelName"), request.getParameter("channelDescription"));
		    	
		    } else if (command.equalsIgnoreCase("createPage")) { // 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "createPage");
		    	output = CApiDashboard.createPage(request.getParameter("pageName"), request.getParameter("path"));
	
		    // [R]ead
		    } else if (command.equalsIgnoreCase("getAllChannels")) { // return all the existing channels
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getAllChannels");
			    output = CApiDashboard.getAllChannels();
			     
		    } else if (command.equalsIgnoreCase("getAllPages")) { // return all the existing pages (to pick pages to be associated with channels)
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getAllPages");
			    output = CApiDashboard.getAllPages();
			     
		    } else if (command.equalsIgnoreCase("getChannel")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getChannel");
			    output = CApiDashboard.getChannel(request.getParameter("channelId"));
			     
		    } else if (command.equalsIgnoreCase("getChannelPages")) { // a channel includes 1-N pages
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getChannelPages");
			    output = CApiDashboard.getChannelPages(request.getParameter("channelId"));
			     
		    } else if (command.equalsIgnoreCase("getPageContent")) { // a page
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "getPageContent");
		    	output = CApiDashboard.getPageContent(request.getParameter("pageId"));
	
		    // [U]pdate
		    } else if (command.equalsIgnoreCase("updateChannel")) { // 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "updateChannel");
		    	output = CApiDashboard.updateChannel(request.getParameter("channelId"), request.getParameter("channelName"), request.getParameter("channelDescription"));
	
		    } else if (command.equalsIgnoreCase("updateChannelPages")) { // 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "updateChannelPages");
		    	output = CApiDashboard.updateChannelPages(request.getParameter("channelId"), request.getParameter("pageIds"));
		    
		    // [D]elete
		    } else if (command.equalsIgnoreCase("deleteChannel")) { //
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "deleteChannel");
		    	output = CApiDashboard.deleteChannel(request.getParameter("channelId"));
		    	
		    } else if (command.equalsIgnoreCase("deletePage")) { 
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "deletePage");
		    	output = CApiDashboard.deletePage(request.getParameter("pageId"));
	
		    } else {
		    	CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "unknown command: " + command + "!");
		    }
	    
		} catch (Exception e) {
			e.printStackTrace();output = CRestUtils.formatCrash("Unexpected error!");
		}

	    
	    if (output.length() > CATALINA_LOG_MAX_SIZE && CStudioGlobals.traceLevel != LOG_PRIORITY_FINE)
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> GET " + command + "() returning " + CTextUtils.left(output, CATALINA_LOG_MAX_SIZE) + "...");
	    else
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> GET " + command + "() returning " + output);

	    //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "response pre-data: " + output);
    	updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
    	
	    PrintWriter out = response.getWriter();
	    
	    /*
	    out.println("command          = " + request.getMethod() + "\n");
	    out.println("ServletUri       = " + request.getRequestURI() + "\n");
	    out.println("ContextPath      = " + request.getContextPath() + "\n");
	    out.println("ServletPath      = " + request.getServletPath() + "\n");
	    out.println("PathInfo         = " + request.getPathInfo() + "\n");
	    out.println("Nom du serveur   = " + request.getServerName() + "\n");
	    out.println("Logiciel utilisé = " + request.getServletContext().getServerInfo() + "\n"); 
	    out.println("Port du serveur  = " + request.getServerPort() + "\n");
	    out.println("Path translated  = " + request.getPathTranslated() + "\n");
		*/
	    
	    if (output == null)
	    	out.println(CRestUtils.formatError("null returned by server"));
	    
	    //System.out.println("- - - - - - RETURNING FINAL: " + CTextUtils.replaceInvisibleCharactersInJson(output));
	    
	    out.println(CTextUtils.replaceInvisibleCharactersInJson(output));
	}
	
    
	
	
	
	
	
	
	
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		addHeadersToResponse(response);

        response.setContentType("application/json; charset=utf-8");
		
	    String output = "{\"result\": \"failure\", \"message\": \"unexpected exit\"} ";

	    String command = getCommand(request);

	    System.out.println(">>>>>>>>>>>> POST COMMAND = " + command);
	    
	    // +-----------------------+
	    // |       COMMON API      |
	    // +-----------------------+

	    if (command.equalsIgnoreCase("authenticate")) {
	        System.out.println("\n\n--------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  POST - authenticate()");
	        
	        // authenticate the user, create the session and start filling session's attributes
	        output = CApiCommon.authenticateUser(request);
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  authenticate() returning " + output);
		    
		    // set a status=200 (SC_OK)                    in case of success
		    // set a status=100 (SC_CONTINUE)              in case of success (i.e. change password required)
		    // set a status=204 (SC_NO_CONTENT)            in case of no-content (i.e. cache client side)
		    // set a status=400 (SC_BAD_REQUEST)           in case of bad-request
		    // set a status=403 (SC_FORBIDDEN)             in case of forbidden
		    // set a status=404 (SC_NOT_FOUND)             in case of failure
		    // set a status=500 (SC_INTERNAL_SERVER_ERROR) in case of crash
		    updateStatusResponse(response, output);

		    PrintWriter out = response.getWriter();
		    out.println(output);
		    
	        // initialize the lastAccessTime
		    if (response.getStatus() == HttpServletResponse.SC_OK) {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "authentication OK");
		        HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.
		    	
		        synchronized(sessionVector) {
		        	sessionVector.add(session);
		        }
		
			    //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "initializing timeout tracker on session [" + session.getId() + "]...");
			    long currentTimeMillis = System.currentTimeMillis();
		    	session.setAttribute("last_access_time", currentTimeMillis);
			    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "initializing last_access_time=" + CCalendarUtils.millisTimeToDateString(currentTimeMillis) + " in session [" + session.getId() + "]...");
			    
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime (poll filtered) = " + CCalendarUtils.millisTimeToDateString((Long)session.getAttribute("last_access_time")));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / user_id                          = " + session.getAttribute("user_id"));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / only_update_user_password        = " + session.getAttribute("only_update_user_password"));
				
		    	CLocalization localization = (CLocalization)session.getAttribute("localization");
		    	//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "localization: " + localization);
		    	String language = localization.getLanguage();
		    	//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "language: " + language);
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / Language                         = " + language);
		    	

		    // password needs to be changed
		    } else if (response.getStatus() == HttpServletResponse.SC_CONTINUE) {
		    	response.setStatus(HttpServletResponse.SC_OK); // set the response status to 200 to force the session to have a valid Cookie (that will be used to call updateUserPassword())
		    	
		    	// ---------------------------------------------------------------------------
		    	// Session needs to be opened (to have the user_id attribute in session
		    	// but this session should only authorize calling the changeUserPassword() API 
		    	// ---------------------------------------------------------------------------
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "the user needs to change his/her password");
		        HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.
		    	
		        synchronized(sessionVector) {
		        	sessionVector.add(session);
		        }
		
		        // Setting a 'only_update_user_password' attribute so that using this session it shouldn't be possible to do anything else than calling updateUserPassword()
		        synchronized(session) {
		            session.setAttribute("only_update_user_password", true);
		        }
		        
			    //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "initializing timeout tracker on session [" + session.getId() + "]...");
			    long currentTimeMillis = System.currentTimeMillis();
		    	session.setAttribute("last_access_time", currentTimeMillis);
			    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "initializing last_access_time=" + CCalendarUtils.millisTimeToDateString(currentTimeMillis) + " in session [" + session.getId() + "]...");
			    
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime (poll filtered) = " + CCalendarUtils.millisTimeToDateString((Long)session.getAttribute("last_access_time")));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / user_id                          = " + session.getAttribute("user_id"));
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / only_update_user_password        = " + session.getAttribute("only_update_user_password"));
		    	
		    	CLocalization localization = (CLocalization)session.getAttribute("localization");
		    	//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "localization: " + localization);
		    	String language = localization.getLanguage();
		    	//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "language: " + language);
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / Language                         = " + language);
		
		    
		    } else {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "authentication KO");
		    	HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.
		    	CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "invalidating session...");
		    	session.invalidate();
		        synchronized(sessionVector) {
		        	sessionVector.remove(session);
		        }
		    }
		    
	        System.out.println("\n\n--------------------------------------------------------------------------------\n\n");
		    return;
		    
	    } else if (command.equalsIgnoreCase("resetUserPassword")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  POST - resetUserPassword()");
		    output = CApiXStudioWeb_Common.resetUserPassword(request.getParameter("username"));
		    CUtils.sleep(2000);
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  resetUserPassword() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return; // skip the error conversion management
		    
		    
		    
	    // +---------------------------------------+
		// |           JIRA ADDON API v2           |
	    // | used with tomcat8.5 with new browsers |
	    // | not supporting cookie/auth in iFrame  |
	    // +---------------------------------------+
	    
		// Requirements
	    } else if (command.equalsIgnoreCase("getPluginRequirementData")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  POST - getPluginRequirementData()");
	        
	        output = CApiCommon.authenticateUser(request); // use the passed parameters "username" and "sha256Password"
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  inner authentication returned " + output);
		    
		    updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
		    if (response.getStatus() != HttpServletResponse.SC_OK) {
		    	PrintWriter out = response.getWriter();
			    out.println(output);
			    return;
		    }

		    // necessarily non-null and valid if we get to this stage
		    HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.

			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / user_id                          = " + session.getAttribute("user_id"));

		    output = CApiPlugin.getPluginRequirementData(session, request.getParameter("requirementId"));
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getPluginRequirementData() returning " + output);
		    
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return;
	   
		// Bugs
	    } else if (command.equalsIgnoreCase("getPluginBugData")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  POST - getPluginBugData()");
	        
	        output = CApiCommon.authenticateUser(request); // use the passed parameters "username" and "sha256Password"
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  inner authentication returned " + output);
		    
		    updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
		    if (response.getStatus() != HttpServletResponse.SC_OK) {
		    	PrintWriter out = response.getWriter();
			    out.println(output);
			    return;
		    }

		    // necessarily non-null and valid if we get to this stage
		    HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.

			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / user_id                          = " + session.getAttribute("user_id"));

		    output = CApiPlugin.getPluginBugData(session, request.getParameter("bugId"));
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  getPluginBugData() returning " + output);
		    
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return;
	    }
	    
	    
	    
	    
	    
	    // At this stage we MUST have a valid session with a user properly authenticated
	    // BUT we check if it has timed out
	    // We test here if the session include the "user_id" and "localization" attributes
	    if (isSessionTimedOut(request, response, !command.equalsIgnoreCase("registerForPushNotification"))) { // we track activity on all the command except "registerForPushNotification"
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "SESSION TIMED OUT");
	    	response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // ERROR 401
		    PrintWriter out = response.getWriter();
			out.println("{\"result\": \"failure\", \"message\": \"Session expired or invalidated!\"}");
			return;
	    }
	    
	    
	    // necessarily non-null and valid if we get to this stage
	    HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.

		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "doGet: valid existing session: " + session.getId());
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / MaxInactiveInterval              = " + session.getMaxInactiveInterval() + " in seconds");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / CreationTime                     = " + CCalendarUtils.millisTimeToDateString(session.getCreationTime()));
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime                 = " + CCalendarUtils.millisTimeToDateString(session.getLastAccessedTime()));
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / LastAccessedTime (poll filtered) = " + CCalendarUtils.millisTimeToDateString((Long)session.getAttribute("last_access_time")));
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / user_id                          = " + session.getAttribute("user_id"));
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] POST / only_update_user_password        = " + session.getAttribute("only_update_user_password"));

	    
		// At this stage we MUST have a valid session with a user properly authenticated
		// but if we have the session attribute "only_update_user_password", only updateUserPassword() should be usable
	    if (command.equalsIgnoreCase("updateUserPassword")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateUserPassword()");
		    output = CApiXStudioWeb_User.updateUserPassword(session, request.getParameter("password"), request.getParameter("token"));
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  updateUserPassword() returning " + output);
		    
		    updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
	       	
		    // if the updateUserPassword() succeeded, let's invalidate the session so that the user can login again with a new fresh session
		    if (response.getStatus() == HttpServletResponse.SC_OK) {
		        // first close all running AsyncContext running on this session and for this user for push notification
		        CApiCommon.closeInvalidatedAsyncContext(session.getId(), Integer.parseInt(""+session.getAttribute("user_id")));
		        
		        CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "]  invalidating session...");
		        session.invalidate();
		        synchronized(sessionVector) {
		        	sessionVector.remove(session);
		        }
		    }
		    
		    PrintWriter out = response.getWriter();
		    
		    if (output == null)
		    	out.println(CRestUtils.formatError("null returned by server"));
		    
		    out.println(CTextUtils.replaceInvisibleCharactersInJson(output));
		    return;
	    }
		
	    if (session.getAttribute("only_update_user_password") != null) {
		    Boolean onlyUpdateChangePassword = (Boolean)session.getAttribute("only_update_user_password");
		    if (onlyUpdateChangePassword == true) {
		    	response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // ERROR 401
			    PrintWriter out = response.getWriter();
				out.println("{\"result\": \"failure\", \"message\": \"Session authorizing only updateChangePassword() calls!\"}");
				return;
		    }
	    }
	    
	    if (command.equalsIgnoreCase("confirmSsoAuthentication")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - confirmSsoAuthentication()");
	        
	        synchronized(sessionVector) {
	        	sessionVector.add(session);
	        }
	        
	        PrintWriter out = response.getWriter();
			out.println("{\"result\": \"success\", \"message\": \"\"}");
			return;
			
	    } else if (command.equalsIgnoreCase("logout")) {
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - logout()");

	        // test if we logged in SSO
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] checking if we logged in with SSO...");
	        boolean isAnSsoSession = CApiCommon.isAnSsoSession(session);
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] SSO session: " + isAnSsoSession);

		    // first close all running AsyncContext running on this session and for this user for push notification
	        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "==> closing contexts");
		    CApiCommon.closeInvalidatedAsyncContext(session.getId(), Integer.parseInt(""+session.getAttribute("user_id")));
		    
		    CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "previous sessionVector: " + sessionVector);
		    synchronized(sessionVector) {
		    	sessionVector.remove(session);
		    }
		    CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "session " + session.getId() + " removed from the storage...");
		    CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "new sessionVector: " + sessionVector);

		    if (isAnSsoSession) {
		    	// In this case the client will redirect to https://xqual-dev-eric1.myxqual.com/xqual/logout and this will forward the logout request to the IDP
		    	// the IDP will invalidate the session
		    } else {
			    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "==> invalidating session...");
			    try {
					session.invalidate(); // the session should be already invalidated in case of a SSO login
				} catch (Exception e) {
					CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Error: couldn't invalidate the session");
					e.printStackTrace();
				}
		    }
		    
		    output = CRestUtils.formatResult(1);
		    CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[-] ==>  logout() returning " + output);
		    PrintWriter out = response.getWriter();
		    out.println(output);
		    return;
	    }
	    
	    // +-----------------------+
	    // |      STANDARD API     |
	    // +-----------------------+

	    try {
		
		    if (command.equalsIgnoreCase("updateServerSettings")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateServerSettings()");
			    output = CApiXStudioWeb_Common.updateServerSettings(session, request);
			    
	    	} else if (command.equalsIgnoreCase("checkUserPassword")) { // only used to re-authenticate when the user signs an item
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - checkUserPassword()");
			    output = CApiXStudioWeb_Common.checkUserPassword(session, request);

	    	} else if (command.equalsIgnoreCase("updateUserPreferences")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateUserPreferences()");
			    output = CApiXStudioWeb_Common.updateUserPreferences(session, request);
			    
		    } else if (command.equalsIgnoreCase("verifySignatures")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifySignatures()");
		        output = CApiXStudioWeb_Common.verifySignatures(session, request);

		        
		        
		        
	    	} else if (command.equalsIgnoreCase("deleteRequirementConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteRequirementConnector()");
			    output = CApiXStudioWeb_Common.deleteRequirementConnector(session, request.getParameter("connectorIndex"));
			    
		    	
	    	} else if (command.equalsIgnoreCase("deleteBugConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteBugConnector()");
			    output = CApiXStudioWeb_Common.deleteBugConnector(session, request.getParameter("connectorIndex"));
			    
		    	
	    	} else if (command.equalsIgnoreCase("clearUserPreferences")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - clearUserPreferences()");
			    output = CApiXStudioWeb_Common.clearUserPreferences(session);
			    
		    	
	    	} else if (command.equalsIgnoreCase("restartServer")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - restartServer()");
			    output = CApiXStudioWeb_Common.restartServer(session, request.getParameter("nightly"));
			    

			// -------------
			// Utilities
			// -------------
			// just for test
	    	} else if (command.equalsIgnoreCase("runDailyProcess")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - runDailyProcess()");
			    output = CApiXStudioWeb_Common.runDailyProcess(session);

	    	} else if (command.equalsIgnoreCase("checkDatabaseIntegrity")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - checkDatabaseIntegrity()");
			    output = CApiXStudioWeb_Common.checkDatabaseIntegrity(session, request);

	    	
		    // Import data (Excel file)
	    	} else if (command.equalsIgnoreCase("importData")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - importData()");
			    output = CApiXStudioWeb_Common.importData(session, request, request.getParameter("filename"), request.getParameter("importType"), request.getParameter("importFormat"), request.getParameter("conflictResolution"));
			    
		    // Import package (marketplace)
	    	} else if (command.equalsIgnoreCase("importPackage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - importData()");
			    output = CApiXStudioWeb_Common.importPackage(session, request, request.getParameter("packageId"));
			    
			// Buy package (marketplace)
	    	} else if (command.equalsIgnoreCase("buyPackage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - importData()");
			    output = CApiXStudioWeb_Common.buyPackage(session, request, request.getParameter("packageId"), request.getParameter("licenseKey"));
				    
			    
			    
	    	
		    } else if (command.equalsIgnoreCase("_setDateIfNull")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - _setDateIfNull()");
			    output = CApiXStudioWeb_Common.setDateIfNull(session, request.getParameter("id"), request.getParameter("date"), request.getParameter("tableName"), request.getParameter("idFieldName"), request.getParameter("dateFieldName"));
			    
			    
		    } else if (command.equalsIgnoreCase("_setDate")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - _setDate()");
			    output = CApiXStudioWeb_Common.setDate(session, request.getParameter("id"), request.getParameter("date"), request.getParameter("tableName"), request.getParameter("idFieldName"), request.getParameter("dateFieldName"));
			    
			    
		    // Photo
		    } else if (command.equalsIgnoreCase("updateUserPhoto")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateUserPhoto()");
			    output = CApiXStudioWeb_User.updateUserPhoto(session, request, request.getParameter("userId"));
			    

		    } else if (command.equalsIgnoreCase("deleteUserPhoto")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteUserPhoto()");
			    output = CApiXStudioWeb_User.deleteUserPhoto(session, request.getParameter("userId"));
			    

	    	
	    	
		    // Attachments
		    } else if (command.equalsIgnoreCase("uploadAttachment")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - uploadAttachment()");
			    output = CApiXStudioWeb_Common.uploadAttachment(session, request, request.getParameter("treeType"), request.getParameter("attachmentName"), request.getParameter("nodeType"), request.getParameter("nodeId"));
			    
		    } else if (command.equalsIgnoreCase("updateAttachment")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAttachment()");
			    output = CApiXStudioWeb_Common.updateAttachment(session, request, request.getParameter("treeType"), request.getParameter("attachmentId"), request.getParameter("attachmentName"), request.getParameter("nodeType"), request.getParameter("nodeId"));

		    } else if (command.equalsIgnoreCase("uploadTestcaseExecutionAttachment")) { // useful when we don't know the testcase execution id
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - uploadTestcaseExecutionAttachment()");
			    output = CApiXStudioWeb_Common.uploadAttachment(session, request, request.getParameter("treeType"), request.getParameter("attachmentName"), request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    
		

		    } else if (command.equalsIgnoreCase("deleteAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteAttachments()");
			    output = CApiXStudioWeb_Common.deleteAttachments(session, request.getParameter("treeType"), request.getParameter("attachmentIds"), request.getParameter("attachmentNames"), request.getParameter("nodeType"), request.getParameter("nodeId"));
			    

		    } else if (command.equalsIgnoreCase("deleteTestcaseExecutionAttachments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTestcaseExecutionAttachments()");
			    output = CApiXStudioWeb_Common.deleteAttachments(session, request.getParameter("treeType"), request.getParameter("attachmentIds"), request.getParameter("attachmentNames"), request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("agentId"), request.getParameter("instanceIndex"));
			    
	
			    

		    } else if (command.equalsIgnoreCase("lockAttachment")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - lockAttachment()");
		        output = CApiXStudioWeb_Common.lockAttachment(session, request, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("attachmentId"));

		    } else if (command.equalsIgnoreCase("unlockAttachment")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unlockAttachment()");
		        output = CApiXStudioWeb_Common.unlockAttachment(session, request, request.getParameter("treeType"), request.getParameter("nodeType"), request.getParameter("attachmentId"));


		        
		        
		        
			    

		    // Embedded Images
		    } else if (command.equalsIgnoreCase("uploadEmbeddedImage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - uploadEmbeddedImage()");
			    output = CApiXStudioWeb_Common.uploadEmbeddedImage(session, request, request.getParameter("embeddedImageName"));
			    
				    
	
			    
			// Custom fields
		    } else if (command.equalsIgnoreCase("createCustomField")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createCustomField()");
			    output = CApiXStudioWeb_Common.createCustomField(session, request, request.getParameter("nodeType"));
			    
	
		    } else if (command.equalsIgnoreCase("updateCustomField")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCustomField()");
			    output = CApiXStudioWeb_Common.updateCustomField(session, request, request.getParameter("nodeType"), request.getParameter("customFieldType"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteCustomFields")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteCustomFields()");
			    output = CApiXStudioWeb_Common.deleteCustomFields(session, request.getParameter("treeType"), request.getParameter("customFieldIds"), request.getParameter("customFieldTypes"));
			    
			
			
			// Audit log
			/*
		    } else if (command.equalsIgnoreCase("insertChange")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertChange()");
			    output = CApiStandard.insertChange(session, request.getParameter("type"), request.getParameter("id"), request.getParameter("action"), request.getParameter("info"));
			    
			*/
		    
		    
		    // Timetags
		    } else if (command.equalsIgnoreCase("createTimetag")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTimetag()");
			    output = CApiXStudioWeb_Common.createTimetag(session, request.getParameter("timetagName"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteTimetag")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTimetag()");
			    output = CApiXStudioWeb_Common.deleteTimetag(session, request.getParameter("timetagId"));
			    
			    
		    } else if (command.equalsIgnoreCase("selectTimetag")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - selectTimetag()");
			    output = CApiXStudioWeb_Common.selectTimetag(session, request.getParameter("timetagId"));
			    
	
			    
			    
			// Dashboard
		    } else if (command.equalsIgnoreCase("setNewFeaturesAsNotified")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - setNewFeaturesAsNotified()");
			    output = CApiXStudioWeb_Dashboard.setNewFeaturesAsNotified(session);
			    


			// Definitions
		    } else if (command.equalsIgnoreCase("createDefinition")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createDefinition()");
			    output = CApiXStudioWeb_Common.createDefinition(session, request);
			    

		    } else if (command.equalsIgnoreCase("deleteDefinitions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteDefinitions()");
		        output = CApiXStudioWeb_Common.deleteDefinitions(session, request.getParameter("definitionIds"));
			    

			    
			// Folders
		    } else if (command.equalsIgnoreCase("createFolder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createFolder()");
			    output = CApiXStudioWeb_Common.createFolder(session, request, request.getParameter("treeType"), request.getParameter("parentFolderId"));
			    
	
		    } else if (command.equalsIgnoreCase("createFolderUnderCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createFolderUnderCategory()");
			    output = CApiXStudioWeb_Common.createFolderUnderCategory(session, request, request.getParameter("parentFolderId"), request.getParameter("categoryId"));
			    
	
		    } else if (command.equalsIgnoreCase("createFolderUnderCompany")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createFolderUnderCompany()");
			    output = CApiXStudioWeb_Common.createFolderUnderCompany(session, request, request.getParameter("treeType"), request.getParameter("parentFolderId"), request.getParameter("companyId"));
			    
	
		    } else if (command.equalsIgnoreCase("createFolderUnderProject")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createFolderUnderProject()");
			    output = CApiXStudioWeb_Common.createFolderUnderProject(session, request, request.getParameter("parentFolderId"), request.getParameter("projectId"));
			    
	
		    } else if (command.equalsIgnoreCase("updateFolderDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateFolderDetails()");
			    output = CApiXStudioWeb_Common.updateFolder(session, request, request.getParameter("treeType"));
			    
			    
		    } else if (command.equalsIgnoreCase("copyFolder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyFolder()");
			    output = CApiXStudioWeb_Common.copyFolder(session, request.getParameter("folderId"), request.getParameter("folderName"), request.getParameter("treeType"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("copyFolderToCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyFolderToCategory()");
			    output = CApiXStudioWeb_Common.copyFolderToCategory(session, request.getParameter("folderId"), request.getParameter("folderName"), request.getParameter("treeType"), request.getParameter("destParentCategoryId"));
			    
			    
		    } else if (command.equalsIgnoreCase("copyFolderToCompany")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyFolderToCompany()");
			    output = CApiXStudioWeb_Common.copyFolderToCompany(session, request.getParameter("folderId"), request.getParameter("folderName"), request.getParameter("treeType"), request.getParameter("destParentCompanyId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToFolder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToFolder()");
			    output = CApiXStudioWeb_Common.moveFoldersToFolder(session, request.getParameter("treeType"), request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("destParentFolderId"), request.getParameter("destParentFolderName"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToProjectFolder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToProjectFolder()");
			    output = CApiXStudioWeb_Common.moveFoldersToProjectFolder(session, request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("destParentProjectFolderId"), request.getParameter("destParentProjectFolderName"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToCategory()");
			    output = CApiXStudioWeb_Common.moveFoldersToCategory(session, request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("destParentCategoryId"), request.getParameter("destParentCategoryName"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToCompany")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToCompany()");
			    output = CApiXStudioWeb_Common.moveFoldersToCompany(session, request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("treeType"), request.getParameter("destParentCompanyId"), request.getParameter("destParentCompanyName"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToProject")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToProject()");
			    output = CApiXStudioWeb_Common.moveFoldersToProject(session, request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("treeType"), request.getParameter("destParentProjectId"), request.getParameter("destParentProjectName"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveFoldersToRoot")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveFoldersToRoot()");
			    output = CApiXStudioWeb_Common.moveFoldersToRoot(session, request.getParameter("folderIds"), request.getParameter("folderNames"), request.getParameter("treeType"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("emptyFolders")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - emptyFolders()");
			    output = CApiXStudioWeb_Common.emptyFolders(session, request.getParameter("treeType"), request.getParameter("folderIds"));
		    } else if (command.equalsIgnoreCase("hardEmptyFolders")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardEmptyFolders()");
			    output = CApiXStudioWeb_Common.hardEmptyFolders(session, request.getParameter("treeType"), request.getParameter("folderIds"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("deleteFolders")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteFolders()");
			    output = CApiXStudioWeb_Common.deleteFolders(session, request.getParameter("treeType"), request.getParameter("folderIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteFolders")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteFolders()");
			    output = CApiXStudioWeb_Common.hardDeleteFolders(session, request.getParameter("treeType"), request.getParameter("folderIds"));
			    
			    
			
			    
			    
			    
		    } else if (command.equalsIgnoreCase("createTreeFilter")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTreeFilter()");
			    output = CApiXStudioWeb_Common.createTreeFilter(session, request, request.getParameter("treeType"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteTreeFilters")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTreeFilters()");
			    output = CApiXStudioWeb_Common.deleteTreeFilters(session, request.getParameter("treeFilterIds"));
			    

			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("createCompany")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createCompany()");
			    output = CApiXStudioWeb_Common.createCompany(session, request);
			    
	
		    } else if (command.equalsIgnoreCase("updateCompanyDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCompanyDetails()");
			    output = CApiXStudioWeb_Common.updateCompany(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("deleteCompanies")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteCompanies()");
			    output = CApiXStudioWeb_Common.deleteCompanies(session, request.getParameter("companyIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteCompanies")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteCompanies()");
			    output = CApiXStudioWeb_Common.hardDeleteCompanies(session, request.getParameter("companyIds"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("createCategory")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createCategory()");
			    output = CApiXStudioWeb_Common.createCategory(session, request);
			    
	
		    } else if (command.equalsIgnoreCase("updateCategoryDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCategoryDetails()");
			    output = CApiXStudioWeb_Common.updateCategory(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("deleteCategories")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteCategories()");
			    output = CApiXStudioWeb_Common.deleteCategories(session, request.getParameter("categoryIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteCategories")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteCategories()");
			    output = CApiXStudioWeb_Common.hardDeleteCategories(session, request.getParameter("categoryIds"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("createMessage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createMessage()");
			    output = CApiXStudioWeb_Common.createMessage(session, request, request.getParameter("nodeType"), request.getParameter("nodeIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("broadcastMessage")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - broadcastMessage()");
			    output = CApiXStudioWeb_Common.broadcastMessage(session, request);
			    
	
			    
			    
			// SUTs
		    } else if (command.equalsIgnoreCase("createSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createSut()");
			    output = CApiXStudioWeb_Sut.createSut(session, request, request.getParameter("parentFolderId"));
			    
	
		    } else if (command.equalsIgnoreCase("updateSutDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSutDetails()");
			    output = CApiXStudioWeb_Sut.updateSut(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("linkSutToRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkSutToRequirements()");
			    output = CApiXStudioWeb_Sut.linkSutToRequirements(session, request, request.getParameter("connectorIndex"), request.getParameter("sutId"));
			    
			    
		    } else if (command.equalsIgnoreCase("insertSutCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSutCustomFieldValue()");
			    output = CApiXStudioWeb_Sut.insertSutCustomFieldValue(session, request.getParameter("sutId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
			    
		    } else if (command.equalsIgnoreCase("copySuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copySuts()");
			    output = CApiXStudioWeb_Sut.copySuts(session, request.getParameter("sutIds"), request.getParameter("sutNames"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveSuts()");
			    output = CApiXStudioWeb_Sut.moveSuts(session, request.getParameter("sutIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("freezeSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeSut()");
		        output = CApiXStudioWeb_Sut.freezeSut(session, request.getParameter("sutId"));
		    } else if (command.equalsIgnoreCase("unfreezeSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeSut()");
		        output = CApiXStudioWeb_Sut.unfreezeSut(session, request.getParameter("sutId"));
		    } else if (command.equalsIgnoreCase("signSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signSut()");
		        output = CApiXStudioWeb_Sut.signSut(session, request.getParameter("sutId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifySutSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifySutSignature()");
		        output = CApiXStudioWeb_Sut.verifySutSignature(session, request.getParameter("sutId"));

	
		    } else if (command.equalsIgnoreCase("deleteSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSuts()");
		        output = CApiXStudioWeb_Sut.deleteSuts(session, request.getParameter("sutIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteSuts()");
		        output = CApiXStudioWeb_Sut.hardDeleteSuts(session, request.getParameter("sutIds"));
			    
			    
			    
			    
			    
			// Requirements
		    } else if (command.equalsIgnoreCase("createRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createRequirement()");
			    output = CApiXStudioWeb_Requirement.createRequirement(session, request, request.getParameter("parentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("createGenericRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createGenericRequirement()");
			    output = CApiXStudioWeb_Requirement.createGenericRequirement(session, request, request.getParameter("parentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("createExternalRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createExternalRequirement()");
			    output = CApiXStudioWeb_Requirement.createExternalRequirement(session, request, request.getParameter("connectorIndex"), request.getParameter("projectName"));
			    
			    
		    } else if (command.equalsIgnoreCase("insertRequirementCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertRequirementCustomFieldValue()");
			    output = CApiXStudioWeb_Requirement.insertRequirementCustomFieldValue(session, request.getParameter("requirementId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("linkRequirementToRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkRequirementToRequirements()");
			    output = CApiXStudioWeb_Requirement.linkRequirementToRequirements(session, request, request.getParameter("connectorIndex1"), request.getParameter("requirementId"), request.getParameter("connectorIndex2"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkRequirementToSuts")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkRequirementToSuts()");
			    output = CApiXStudioWeb_Requirement.linkRequirementToSuts(session, request, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkRequirementToSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkRequirementToSpecifications()");
			    output = CApiXStudioWeb_Requirement.linkRequirementToSpecifications(session, request, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkRequirementToTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkRequirementToTests()");
			    output = CApiXStudioWeb_Requirement.linkRequirementToTests(session, request, request.getParameter("connectorIndex"), request.getParameter("requirementId"));
			    
			    
			    		    
			    
		    } else if (command.equalsIgnoreCase("copyRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyRequirements()");
			    output = CApiXStudioWeb_Requirement.copyRequirements(session, request.getParameter("requirementIds"), request.getParameter("requirementNames"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveRequirements()");
			    output = CApiXStudioWeb_Requirement.moveRequirements(session, request.getParameter("requirementIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveGenericRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveGenericRequirements()");
			    output = CApiXStudioWeb_Requirement.moveGenericRequirements(session, request.getParameter("requirementIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("freezeRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeRequirement()");
		        output = CApiXStudioWeb_Requirement.freezeRequirement(session, request.getParameter("requirementId"));
		    } else if (command.equalsIgnoreCase("unfreezeRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeRequirement()");
		        output = CApiXStudioWeb_Requirement.unfreezeRequirement(session, request.getParameter("requirementId"));
		    } else if (command.equalsIgnoreCase("signRequirement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signRequirement()");
		        output = CApiXStudioWeb_Requirement.signRequirement(session, request.getParameter("requirementId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyRequirementSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyRequirementSignature()");
		        output = CApiXStudioWeb_Requirement.verifyRequirementSignature(session, request.getParameter("requirementId"));

	
		    } else if (command.equalsIgnoreCase("deleteRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteRequirements()");
		        output = CApiXStudioWeb_Requirement.deleteRequirements(session, request.getParameter("requirementIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteRequirements()");
		        output = CApiXStudioWeb_Requirement.hardDeleteRequirements(session, request.getParameter("requirementIds"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteGenericRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteGenericRequirements()");
		        output = CApiXStudioWeb_Requirement.deleteGenericRequirements(session, request.getParameter("requirementIds"));
			    
	
		    } else if (command.equalsIgnoreCase("updateRequirementDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateRequirementDetails()");
			    output = CApiXStudioWeb_Requirement.updateRequirement(session, request);
			    
	
			    
			    

		    } else if (command.equalsIgnoreCase("updateRequirementsStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateRequirementsStatus()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_REQUIREMENT, request.getParameter("requirementIds"), 5, request.getParameter("status")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateRequirementsPriority")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateRequirementsPriority()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_REQUIREMENT, request.getParameter("requirementIds"), 4, request.getParameter("priority")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateRequirementsRisk")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateRequirementsRisk()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_REQUIREMENT, request.getParameter("requirementIds"), 8, request.getParameter("risk")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateRequirementsType")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateRequirementsType()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_REQUIREMENT, request.getParameter("requirementIds"), 6, request.getParameter("type")); // localized string
			    

			    
			    
			    
			    
			// Specifications
		    } else if (command.equalsIgnoreCase("createSpecification")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createSpecification()");
			    output = CApiXStudioWeb_Specification.createSpecification(session, request, request.getParameter("parentFolderId"));
			    
	
		    } else if (command.equalsIgnoreCase("insertSpecificationCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSpecificationCustomFieldValue()");
			    output = CApiXStudioWeb_Specification.insertSpecificationCustomFieldValue(session, request.getParameter("specificationId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
	
		    } else if (command.equalsIgnoreCase("linkSpecificationToSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkSpecificationToSpecifications()");
			    output = CApiXStudioWeb_Specification.linkSpecificationToSpecifications(session, request, request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkSpecificationToRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkSpecificationToRequirements()");
			    output = CApiXStudioWeb_Specification.linkSpecificationToRequirements(session, request, request.getParameter("connectorIndex"), request.getParameter("specificationId"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkSpecificationToTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkSpecificationToTests()");
			    output = CApiXStudioWeb_Specification.linkSpecificationToTests(session, request, request.getParameter("specificationId"));
			    
	
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("copySpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copySpecifications()");
			    output = CApiXStudioWeb_Specification.copySpecifications(session, request.getParameter("specificationIds"), request.getParameter("specificationNames"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveSpecifications()");
			    output = CApiXStudioWeb_Specification.moveSpecifications(session, request.getParameter("specificationIds"), request.getParameter("destParentFolderId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("freezeSpecification")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeSpecification()");
		        output = CApiXStudioWeb_Specification.freezeSpecification(session, request.getParameter("specificationId"));
		    } else if (command.equalsIgnoreCase("unfreezeSpecification")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeSpecification()");
		        output = CApiXStudioWeb_Specification.unfreezeSpecification(session, request.getParameter("specificationId"));
		    } else if (command.equalsIgnoreCase("signSpecification")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signSpecification()");
		        output = CApiXStudioWeb_Specification.signSpecification(session, request.getParameter("specificationId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifySpecificationSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifySpecificationSignature()");
		        output = CApiXStudioWeb_Specification.verifySpecificationSignature(session, request.getParameter("specificationId"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSpecifications()");
		        output = CApiXStudioWeb_Specification.deleteSpecifications(session, request.getParameter("specificationIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteSpecifications()");
		        output = CApiXStudioWeb_Specification.hardDeleteSpecifications(session, request.getParameter("specificationIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateSpecificationDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSpecificationDetails()");
			    output = CApiXStudioWeb_Specification.updateSpecification(session, request);
			    
	
	
		    } else if (command.equalsIgnoreCase("updateSpecificationsStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSpecificationsStatus()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_SPECIFICATION, request.getParameter("specificationIds"), 4, request.getParameter("status")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateSpecificationsPriority")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSpecificationsPriority()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_SPECIFICATION, request.getParameter("specificationIds"), 5, request.getParameter("priority")); // integer
			    


	
			// Attributes and Params
		    } else if (command.equalsIgnoreCase("createAttribute")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createAttribute()");
			    output = CApiXStudioWeb_Test.createAttribute(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("updateAttribute")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAttribute()");
			    output = CApiXStudioWeb_Test.updateAttribute(session, request, request.getParameter("attributeType"));
			    

		    } else if (command.equalsIgnoreCase("deleteAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteAttributes()");
		        output = CApiXStudioWeb_Test.deleteAttributes(session, request.getParameter("attributeTypes"), request.getParameter("attributeIds"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("createParameter")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createParameter()");
			    output = CApiXStudioWeb_Test.createParameter(session, request);
			    

		    } else if (command.equalsIgnoreCase("updateParameter")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateParameter()");
			    output = CApiXStudioWeb_Test.updateParameter(session, request, request.getParameter("parameterType"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteParameters")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteParameters()");
		        output = CApiXStudioWeb_Test.deleteParameters(session, request.getParameter("parameterTypes"), request.getParameter("parameterIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("getCombinationsOfParameterValues")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - getCombinationsOfParameterValues()");
			    output = CApiXStudioWeb_Test.getCombinationsOfParameterValues(session, request, request.getParameter("algorithm"));
			    

			    
			    
			// Test Scanning
		    } else if (command.equalsIgnoreCase("createScanningJob")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createScanningJob()");
			    output = CApiXStudioWeb_Test.createScanningJob(session, request, request.getParameter("categoryId"), request.getParameter("confId"), request.getParameter("agentId"));
			    

			    
			    
			// Tests
		    } else if (command.equalsIgnoreCase("createTest")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTest()");
			    output = CApiXStudioWeb_Test.createTest(session, request, request.getParameter("parentFolderId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("generateTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTest()");
			    output = CApiXStudioWeb_Test.generateTests(session, request, request.getParameter("treeType"), request.getParameter("srcFolderId"), request.getParameter("destFolderId"),
			    															 request.getParameter("defaultTestType"), request.getParameter("defaultTestStatus"), request.getParameter("defaultTestPriority"),
			    															 request.getParameter("renameFolderIfExisting"), request.getParameter("renameTestIfExisting"));
			    
		    } else if (command.equalsIgnoreCase("generateTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - generateTestcases()");
			    output = CApiXStudioWeb_Test.generateTestcases(session, request, request.getParameter("exploratorySessionId"), request.getParameter("destParentFolderId"));


			    
			    
		    } else if (command.equalsIgnoreCase("updateTestDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestDetails()");
			    output = CApiXStudioWeb_Test.updateTestDetails(session, request);
			    
/*
		    } else if (command.equalsIgnoreCase("updateTestScope")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestScope()");
			    output = CApiXStudioWeb_Test.updateTestScope(session, request, request.getParameter("testId"));
*/
			    
		    } else if (command.equalsIgnoreCase("updateTestAuthor")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestAuthor()");
			    output = CApiXStudioWeb_Test.updateTestAuthor(session, request.getParameter("testId"), request.getParameter("userId"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestDeveloper")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestDeveloper()");
			    output = CApiXStudioWeb_Test.updateTestDeveloper(session, request.getParameter("testId"), request.getParameter("userId"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestTestcases()");
			    output = CApiXStudioWeb_Test.updateTestTestcases(session, request, request.getParameter("testId"), request.getParameter("testcaseIds"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestsStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestsStatus()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_TEST, request.getParameter("testIds"), 10, request.getParameter("status")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateTestsPriority")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestsPriority()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_TEST, request.getParameter("testIds"), 2, request.getParameter("priority")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateTestsType")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestsType()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_TEST, request.getParameter("testIds"), 13, request.getParameter("type")); // localized string
			    


			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestEstimatedTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestEstimatedTime()");
			    output = CApiXStudioWeb_Test.updateTestEstimatedTime(session, request.getParameter("testId"), request.getParameter("estimatedTime"));
			    


		    } else if (command.equalsIgnoreCase("_updateScanningJob")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - _updateScanningJob()");
			    output = CApiXStudioWeb_Test._updateScanningJob(session, request, request.getParameter("scanningJobId"), request.getParameter("scanningJobStatus"));
			    
			    
			    
			    
			
		    } else if (command.equalsIgnoreCase("insertTestCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertTestCustomFieldValue()");
			    output = CApiXStudioWeb_Test.insertTestCustomFieldValue(session, request.getParameter("testId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("insertTestAttributeValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertTestAttributeValue()");
			    output = CApiXStudioWeb_Test.insertTestAttributeValue(session, request.getParameter("testId"), request.getParameter("attributeType"), request.getParameter("attributeId"), request.getParameter("attributeValue"));
			    
			    
		    } else if (command.equalsIgnoreCase("removeTestAttributeValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeTestAttributeValue()");
			    output = CApiXStudioWeb_Test.removeTestAttributeValue(session, request.getParameter("testId"), request.getParameter("attributeType"), request.getParameter("attributeId"));
			    
			    
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("linkTestToRequirements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkTestToRequirements()");
			    output = CApiXStudioWeb_Test.linkTestToRequirements(session, request, request.getParameter("connectorIndex"), request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("linkTestToSpecifications")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkTestToSpecifications()");
			    output = CApiXStudioWeb_Test.linkTestToSpecifications(session, request, request.getParameter("testId"));
			    

		    } else if (command.equalsIgnoreCase("copyTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyTests()");
			    output = CApiXStudioWeb_Test.copyTests(session, request.getParameter("testIds"), request.getParameter("testNames"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveTests()");
			    output = CApiXStudioWeb_Test.moveTests(session, request.getParameter("testIds"), request.getParameter("destParentFolderId"));
			    

		    } else if (command.equalsIgnoreCase("freezeTest")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeTest()");
		        output = CApiXStudioWeb_Test.freezeTest(session, request.getParameter("testId"));
		    } else if (command.equalsIgnoreCase("unfreezeTest")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeTest()");
		        output = CApiXStudioWeb_Test.unfreezeTest(session, request.getParameter("testId"));
		    } else if (command.equalsIgnoreCase("signTest")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signTest()");
		        output = CApiXStudioWeb_Test.signTest(session, request.getParameter("testId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyTestSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyTestSignature()");
		        output = CApiXStudioWeb_Test.verifyTestSignature(session, request.getParameter("testId"));

	
	
		    } else if (command.equalsIgnoreCase("deleteTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTests()");
		        output = CApiXStudioWeb_Test.deleteTests(session, request.getParameter("testIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteTests()");
		        output = CApiXStudioWeb_Test.hardDeleteTests(session, request.getParameter("testIds"));
			    
			    
			    
			    
			    
			    
			    
			    
			// Testcases
		    } else if (command.equalsIgnoreCase("createTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTestcase()");
			    output = CApiXStudioWeb_Test.createTestcase(session, request, request.getParameter("testId"));
			    
			    
		    } else if (command.equalsIgnoreCase("createTestcaseReferencingReusableTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTestcaseReferencingReusableTestcase()");
			    output = CApiXStudioWeb_Test.createTestcaseReusableTestcase(session, request, request.getParameter("testId"), request.getParameter("reusableTestcaseId")); // reusableTestcaseId to remove
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestcaseDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestcaseDetails()");
			    output = CApiXStudioWeb_Test.updateTestcaseDetails(session, request);
			    
		    } else if (command.equalsIgnoreCase("updateTestcaseProcedure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestcaseProcedure()");
			    output = CApiXStudioWeb_Test.updateTestcaseProcedure(session, request, request.getParameter("testcaseId"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestcasesReadyForManualExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestcasesReadyForManualExecution()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_TESTCASE, request.getParameter("testcaseIds"), 8, request.getParameter("readyForManualExecution")); // boolean
			    
			    
		    } else if (command.equalsIgnoreCase("updateTestcasesReadyForAutomatedExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestcasesReadyForAutomatedExecution()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_TESTCASE, request.getParameter("testcaseIds"), 3, request.getParameter("readyForAutomatedExecution")); // boolean
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("insertTestcaseCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertTestcaseCustomFieldValue()");
			    output = CApiXStudioWeb_Test.insertTestcaseCustomFieldValue(session, request.getParameter("testcaseId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("insertTestcaseParameterValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertTestcaseParameterValue()");
			    output = CApiXStudioWeb_Test.insertTestcaseParameterValue(session, request.getParameter("testcaseId"), request.getParameter("parameterType"), request.getParameter("parameterId"), request.getParameter("parameterValue"));
			    
			    
		    } else if (command.equalsIgnoreCase("removeTestcaseParameterValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeTestcaseParameterValue()");
			    output = CApiXStudioWeb_Test.removeTestcaseParameterValue(session, request.getParameter("testcaseId"), request.getParameter("parameterType"), request.getParameter("parameterId"));
			    
			    

		    
		    
		    } else if (command.equalsIgnoreCase("copyTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyTestcases()");
			    output = CApiXStudioWeb_Test.copyTestcases(session, request.getParameter("testcaseIds"), request.getParameter("testcaseNames"), request.getParameter("destTestId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveTestcases()");
			    output = CApiXStudioWeb_Test.moveTestcases(session, request.getParameter("testcaseIds"), request.getParameter("destTestId"));
			    

			    
			    
			    			    
		    } else if (command.equalsIgnoreCase("freezeTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeTestcase()");
		        output = CApiXStudioWeb_Test.freezeTestcase(session, request.getParameter("testcaseId"));
		    } else if (command.equalsIgnoreCase("unfreezeTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeTestcase()");
		        output = CApiXStudioWeb_Test.unfreezeTestcase(session, request.getParameter("testcaseId"));
		    } else if (command.equalsIgnoreCase("signTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signTestcase()");
		        output = CApiXStudioWeb_Test.signTestcase(session, request.getParameter("testcaseId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyTestcaseSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyTestcaseSignature()");
		        output = CApiXStudioWeb_Test.verifyTestcaseSignature(session, request.getParameter("testcaseId"));

	
			    
		    } else if (command.equalsIgnoreCase("deleteTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTestcases()");
		        output = CApiXStudioWeb_Test.deleteTestcases(session, request.getParameter("testcaseIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteTestcases()");
		        output = CApiXStudioWeb_Test.hardDeleteTestcases(session, request.getParameter("testcaseIds"));
			    
			    
		        
		        
			    
			// Campaigns
		    } else if (command.equalsIgnoreCase("createCampaign")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createCampaign()");
			    output = CApiXStudioWeb_Campaign.createCampaign(session, request, request.getParameter("parentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("createResidualCampaign")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createResidualCampaign()");
			    output = CApiXStudioWeb_Campaign.createResidualCampaign(session, request, request.getParameter("destParentFolderId"), request.getParameter("srcSessionIds"), request.getParameter("rulesMask"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateCampaignDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCampaignDetails()");
			    output = CApiXStudioWeb_Campaign.updateCampaignDetails(session, request);
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateCampaignTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCampaignTests()");
			    output = CApiXStudioWeb_Campaign.updateCampaignTests(session, request.getParameter("campaignId"), request.getParameter("testIds"));
			    
		    } else if (command.equalsIgnoreCase("updateCampaignAddTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCampaignAddTests()");
			    output = CApiXStudioWeb_Campaign.updateCampaignAddTests(session, request.getParameter("campaignId"), request.getParameter("testIds"));
			    
		    } else if (command.equalsIgnoreCase("updateCampaignRemoveTests")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCampaignRemoveTests()");
			    output = CApiXStudioWeb_Campaign.updateCampaignRemoveTests(session, request.getParameter("campaignId"), request.getParameter("testIds"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateCampaignTestsOrder")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateCampaignTestsOrder()");
			    output = CApiXStudioWeb_Campaign.updateCampaignTestsOrder(session, request.getParameter("campaignId"), request.getParameter("testIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteCampaigns()");
		        output = CApiXStudioWeb_Campaign.deleteCampaigns(session, request.getParameter("campaignIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteCampaigns()");
		        output = CApiXStudioWeb_Campaign.hardDeleteCampaigns(session, request.getParameter("campaignIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveCampaigns()");
		        output = CApiXStudioWeb_Campaign.moveCampaigns(session, request.getParameter("campaignIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("copyCampaigns")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyCampaign()");
		        output = CApiXStudioWeb_Campaign.copyCampaigns(session, request.getParameter("campaignIds"), request.getParameter("campaignNames"), request.getParameter("destParentFolderId"));
			    
			    
			} else if (command.equalsIgnoreCase("freezeCampaign")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeCampaign()");
				output = CApiXStudioWeb_Campaign.freezeCampaign(session, request.getParameter("campaignId"));
			} else if (command.equalsIgnoreCase("unfreezeCampaign")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeCampaign()");
				output = CApiXStudioWeb_Campaign.unfreezeCampaign(session, request.getParameter("campaignId"));
			} else if (command.equalsIgnoreCase("signCampaign")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signCampaign()");
				output = CApiXStudioWeb_Campaign.signCampaign(session, request.getParameter("campaignId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyCampaignSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyCampaignSignature()");
		        output = CApiXStudioWeb_Campaign.verifyCampaignSignature(session, request.getParameter("campaignId"));

			
				

				
			} else if (command.equalsIgnoreCase("deleteDeprecatedResultsFromCampaign")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteDeprecatedResultsFromCampaign()");
				output = CApiXStudioWeb_Campaign.deleteDeprecatedResultsFromCampaign(session, request.getParameter("campaignId"));
				
			
			} else if (command.equalsIgnoreCase("deleteAllDeprecatedResults")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteAllDeprecatedResults()");
				output = CApiXStudioWeb_Campaign.deleteAllDeprecatedResults(session);
				
			
				
				
				
				
				
				
				
				
		    } else if (command.equalsIgnoreCase("createSchedule")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createSchedule()");
			    output = CApiXStudioWeb_Campaign.createSchedule(session, request, request.getParameter("campaignId"),
			    																  request.getParameter("sutId"),
			    																  // optional params:
				    															  request.getParameter("parentFailedRule"),
				    															  request.getParameter("parentUnknownRule"), 
			    																  request.getParameter("monitoringAgentId"),
			    																  request.getParameter("monitoringConfigurationId"), 
			    																  request.getParameter("pick"));
			    

		    } else if (command.equalsIgnoreCase("updateScheduleDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleDetails()");
			    output = CApiXStudioWeb_Campaign.updateScheduleDetails(session, request);
			    

		    } else if (command.equalsIgnoreCase("updateScheduleScheduling")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleScheduling()");
			    output = CApiXStudioWeb_Campaign.updateScheduleScheduling(session, request, request.getParameter("scheduleId"));
			    

		    } else if (command.equalsIgnoreCase("updateScheduleAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleAgents()");
			    output = CApiXStudioWeb_Campaign.updateScheduleAgents(session, request.getParameter("scheduleId"), request.getParameter("agentIds"), request.getParameter("nbInstances"), request.getParameter("synchronizations"), request.getParameter("pick"));
			    

		    } else if (command.equalsIgnoreCase("updateScheduleConfigurations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleConfigurations()");
			    output = CApiXStudioWeb_Campaign.updateScheduleConfigurations(session, request, request.getParameter("scheduleId"), request.getParameter("categoryIds"), request.getParameter("configurationIds"));
			    

		    } else if (command.equalsIgnoreCase("updateScheduleOperator")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleOperator()");
			    output = CApiXStudioWeb_Campaign.updateScheduleOperator(session, request.getParameter("scheduleId"), request.getParameter("operatorId"));
			    

		    } else if (command.equalsIgnoreCase("updateScheduleSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateScheduleSut()");
			    output = CApiXStudioWeb_Campaign.updateScheduleSut(session, request.getParameter("scheduleId"), request.getParameter("sutId"));
			    

			    
		    } else if (command.equalsIgnoreCase("updateSchedulesFollowers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSchedulesFollowers()");
			    output = CApiXStudioWeb_Campaign.updateSchedulesFollowers(session, request.getParameter("scheduleIds"), request.getParameter("followerIds"));
			    
		    } else if (command.equalsIgnoreCase("addSchedulesFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - addSchedulesFollower()");
			    output = CApiXStudioWeb_Campaign.addSchedulesFollower(session, request.getParameter("scheduleIds"), request.getParameter("followerId"));
			    
		    } else if (command.equalsIgnoreCase("removeSchedulesFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeSchedulesFollower()");
			    output = CApiXStudioWeb_Campaign.removeSchedulesFollower(session, request.getParameter("scheduleIds"), request.getParameter("followerId"));

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("deleteSchedules")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSchedules()");
		        output = CApiXStudioWeb_Campaign.deleteSchedules(session, request.getParameter("scheduleIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteSchedules")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteSchedules()");
		        output = CApiXStudioWeb_Campaign.hardDeleteSchedules(session, request.getParameter("scheduleIds"));
			    

		    } else if (command.equalsIgnoreCase("copySchedules")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copySchedules()");
			    output = CApiXStudioWeb_Campaign.copySchedules(session, request.getParameter("scheduleIds"), request.getParameter("scheduleNames"), request.getParameter("destCampaignId"));
			    


				
			    
			    
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("createSession")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createSession()");
			    output = CApiXStudioWeb_Campaign.createSession(session, request, request.getParameter("campaignId"),
			    																 request.getParameter("sutId"),
			    																 // optional params:
			    																 request.getParameter("scheduleId"),
			    																 request.getParameter("parentFailedRule"),
			    																 request.getParameter("parentUnknownRule"), 
			    																 request.getParameter("monitoringAgentId"),
			    																 request.getParameter("monitoringConfigurationId"), 
			    																 request.getParameter("pick"));
			    

		    } else if (command.equalsIgnoreCase("mergeSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - mergeSessions()");
			    output = CApiXStudioWeb_Campaign.mergeSessions(session, request, request.getParameter("sessionIds"), request.getParameter("destParentFolderId"), request.getParameter("mergeType"));
			    
			    
		    } else if (command.equalsIgnoreCase("initializeSessionResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - initializeSessionResults()");
			    output = CApiXStudioWeb_Campaign.initializeSessionResults(session, request, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionDetails()");
			    output = CApiXStudioWeb_Campaign.updateSessionDetails(session, request);
			    

		    } else if (command.equalsIgnoreCase("updateSessionConfigurations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertCampaignSessionConfigurations()");
			    output = CApiXStudioWeb_Campaign.updateSessionConfigurations(session, request, request.getParameter("sessionId"), request.getParameter("categoryIds"), request.getParameter("configurationIds"));
			    


			    
		    } else if (command.equalsIgnoreCase("updateSessionOperator")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionOperator()");
			    output = CApiXStudioWeb_Campaign.updateSessionOperator(session, request.getParameter("sessionId"), request.getParameter("operatorId"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionSut()");
			    output = CApiXStudioWeb_Campaign.updateSessionSut(session, request.getParameter("sessionId"), request.getParameter("sutId"));
			    

			    
			// Status: NEW, ACK, APPROVED
		    } else if (command.equalsIgnoreCase("updateSessionsStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionsStatus()");
		        output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_CAMPAIGNSESSION, request.getParameter("sessionIds"), 8, request.getParameter("status")); // integer
			    
		    // State: INITIALIZED, ARMED, RUNNING, PAUSED, TERMINATE_REQUEST, TERMINATING, TERMINATED
		    } else if (command.equalsIgnoreCase("updateSessionState")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionState()");
		        output = CApiXStudioWeb_Campaign.updateSessionState(session, request.getParameter("sessionId"), request.getParameter("state")); // integer
			    

		    // State: INITIALIZED, ARMED, RUNNING, PAUSED, TERMINATE_REQUEST, TERMINATING, TERMINATED
		    } else if (command.equalsIgnoreCase("updateSessionAgentState")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionAgentState()");
			    output = CApiXStudioWeb_Campaign.updateSessionAgentState(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("state")); // integer
			    

			    
		    } else if (command.equalsIgnoreCase("updateSessionGeneratedSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionGeneratedSut()");
			    output = CApiXStudioWeb_Campaign.updateSessionGeneratedSut(session, request.getParameter("sessionId"), request.getParameter("originalSutId"), request.getParameter("sutName"), request.getParameter("sutVersion"));
			    
				
				
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateSessionMonitoredServers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionMonitoredServers()");
			    output = CApiXStudioWeb_Campaign.updateSessionMonitoredServers(session, request.getParameter("sessionId"), request.getParameter("monitoredServerIds"));
			    

		    } else if (command.equalsIgnoreCase("insertMonitoringResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertMonitoringResults()");
			    output = CApiXStudioWeb_Campaign.insertMonitoringResults(session, request, request.getParameter("monitorId"));
			   
			    
			    
				
			    
			    
		    } else if (command.equalsIgnoreCase("updateSessionStartDateTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionStartDateTime()");
			    output = CApiXStudioWeb_Campaign.updateSessionStartDateTime(session, request.getParameter("sessionId"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionStopDateTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionStopDateTime()");
			    output = CApiXStudioWeb_Campaign.updateSessionStopDateTime(session, request.getParameter("sessionId"));
			    

			    
			    
			    
			    
		    // Import results
	    	} else if (command.equalsIgnoreCase("importResults")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - importResults()");
			    output = CApiXStudioWeb_Campaign.importResults(session, request, request.getParameter("sessionId"), request.getParameter("filename"), request.getParameter("importFormat"));
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateSessionsFollowers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionsFollowers()");
			    output = CApiXStudioWeb_Campaign.updateSessionsFollowers(session, request.getParameter("sessionIds"), request.getParameter("followerIds"));
			    
		    } else if (command.equalsIgnoreCase("addSessionsFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - addSessionsFollower()");
			    output = CApiXStudioWeb_Campaign.addSessionsFollower(session, request.getParameter("sessionIds"), request.getParameter("followerId"));
			    
		    } else if (command.equalsIgnoreCase("removeSessionsFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeSessionsFollower()");
			    output = CApiXStudioWeb_Campaign.removeSessionsFollower(session, request.getParameter("sessionIds"), request.getParameter("followerId"));
			    

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateSessionAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionAgents()");
			    output = CApiXStudioWeb_Campaign.updateSessionAgents(session, request.getParameter("sessionId"), request.getParameter("agentIds"), request.getParameter("nbInstances"), request.getParameter("synchronizations"), request.getParameter("pick"));
			    

			    

			    
			    
		    } else if (command.equalsIgnoreCase("insertSessionAgent")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSessionAgent()");
			    output = CApiXStudioWeb_Campaign.insertSessionAgent(session, request.getParameter("sessionId"), request.getParameter("agentId"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("insertSessionTestResult")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSessionTestResult()");
			    output = CApiXStudioWeb_Campaign.insertSessionTestResult(session, request, request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("instanceId"), request.getParameter("result"), request.getParameter("timestamp"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionTestResult")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionTestResult()");
			    output = CApiXStudioWeb_Campaign.updateSessionTestResult(session, request.getParameter("sessionId"), request.getParameter("instanceId"), request.getParameter("testId"), request.getParameter("result"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("insertSessionTestcaseResult")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSessionTestcaseResult()");
			    output = CApiXStudioWeb_Campaign.insertSessionTestcaseResult(session, request, request.getParameter("sessionId"), request.getParameter("testcaseId"), request.getParameter("instanceId"), request.getParameter("result"), request.getParameter("timestamp"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionTestcaseResult")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionTestcaseResult()");
			    output = CApiXStudioWeb_Campaign.updateSessionTestcaseResult(session, request, request.getParameter("sessionId"), request.getParameter("instanceId"), request.getParameter("testcaseId"));
			    

		    } else if (command.equalsIgnoreCase("updateSessionTestcasesResult")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSessionTestcasesResult()");
			    output = CApiXStudioWeb_Campaign.insertSessionTestcasesResult(session, request, request.getParameter("sessionId"), request.getParameter("testcaseIds"), request.getParameter("instanceId"), request.getParameter("result"), request.getParameter("timestamp"), request.getParameter("clearComments"));
			    

			    
			  
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("deleteSessionAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSessionAttributes()");
		        output = CApiXStudioWeb_Campaign.deleteSessionAttributes(session, request.getParameter("sessionId"));
			    
		    } else if (command.equalsIgnoreCase("insertSessionAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSessionAttributes()");
			    output = CApiXStudioWeb_Campaign.insertSessionAttributes(session, request, request.getParameter("sessionId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("deleteSessionParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSessionParams()");
		        output = CApiXStudioWeb_Campaign.deleteSessionParams(session, request.getParameter("sessionId"));
			    
		    } else if (command.equalsIgnoreCase("insertSessionParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertSessionParams()");
			    output = CApiXStudioWeb_Campaign.insertSessionParams(session, request, request.getParameter("sessionId"));
			    
			    

		    } else if (command.equalsIgnoreCase("deleteScheduleAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteScheduleAttributes()");
		        output = CApiXStudioWeb_Campaign.deleteScheduleAttributes(session, request.getParameter("sessionId"));

		    } else if (command.equalsIgnoreCase("insertScheduleAttributes")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertScheduleAttributes()");
			    output = CApiXStudioWeb_Campaign.insertScheduleAttributes(session, request, request.getParameter("scheduleId"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("deleteScheduleParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteScheduleParams()");
		        output = CApiXStudioWeb_Campaign.deleteScheduleParams(session, request.getParameter("scheduleId"));
			    
		    } else if (command.equalsIgnoreCase("insertScheduleParams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertScheduleParams()");
			    output = CApiXStudioWeb_Campaign.insertScheduleParams(session, request, request.getParameter("scheduleId"));
			    

			    
			    
			    
			    
			    
			    
	
		    } else if (command.equalsIgnoreCase("linkTestExecutionsToBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkTestExecutionToBugs()");
			    output = CApiXStudioWeb_Campaign.linkTestExecutionsToBugs(session, request, request.getParameter("connectorIndex"), request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("instanceIndex"), request.getParameter("testIds"));
			    
			    

/*
		    } else if (command.equalsIgnoreCase("updateSessionConfiguration")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - updateSessionConfiguration()");
		        output = CApiXStudioWeb_Campaign.updateSessionConfiguration(session, request.getParameter("sessionId"), request.getParameter("categoryId"), request.getParameter("configurationId"));
			    
*/

		    } else if (command.equalsIgnoreCase("deleteSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSessions()");
		        output = CApiXStudioWeb_Campaign.deleteSessions(session, request.getParameter("sessionIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteSessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteSessions()");
		        output = CApiXStudioWeb_Campaign.hardDeleteSessions(session, request.getParameter("sessionIds"));
			    
			    
			    
		    } else if (command.equalsIgnoreCase("copySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copySessions()");
			    output = CApiXStudioWeb_Campaign.copySessions(session, request.getParameter("sessionIds"), request.getParameter("sessionNames"), request.getParameter("destCampaignId"));
			    
			    
			} else if (command.equalsIgnoreCase("freezeSession")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeSession()");
				output = CApiXStudioWeb_Campaign.freezeSession(session, request.getParameter("sessionId"));
			} else if (command.equalsIgnoreCase("unfreezeSession")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeSession()");
				output = CApiXStudioWeb_Campaign.unfreezeSession(session, request.getParameter("sessionId"));
			} else if (command.equalsIgnoreCase("signSession")) {
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
				CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signSession()");
				output = CApiXStudioWeb_Campaign.signSession(session, request.getParameter("sessionId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifySessionSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifySessionSignature()");
		        output = CApiXStudioWeb_Campaign.verifySessionSignature(session, request.getParameter("sessionId"));

				
				
				
		    } else if (command.equalsIgnoreCase("createConfiguration")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createConfiguration()");
			    output = CApiXStudioWeb_Campaign.createConfiguration(session, request, request.getParameter("categoryId"), request.getParameter("configurationName"));

		    } else if (command.equalsIgnoreCase("copyConfiguration")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyConfiguration()");
			    output = CApiXStudioWeb_Campaign.copyConfiguration(session, request, request.getParameter("configurationId"), request.getParameter("configurationName"));

		    } else if (command.equalsIgnoreCase("deleteConfigurations")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteConfigurations()");
		        output = CApiXStudioWeb_Campaign.deleteConfigurations(session, request.getParameter("configurationIds"));
			    

			    
			    
		    } else if (command.equalsIgnoreCase("insertInstanceCategorySyncId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertInstanceCategorySyncId()");
			    output = CApiXStudioWeb_Campaign.insertInstanceCategorySyncId(session, request.getParameter("instanceId"), request.getParameter("categoryId"), request.getParameter("syncId"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteInstanceSyncId")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteInstanceSyncId()");
			    output = CApiXStudioWeb_Campaign.deleteInstanceSyncId(session, request.getParameter("instanceId"), request.getParameter("categoryId"));
			    

		    } else if (command.equalsIgnoreCase("deleteSelfService")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSelfService()");
			    output = CApiXStudioWeb_Campaign.deleteSelfService(session, request.getParameter("sessionId"), request.getParameter("agentId"), request.getParameter("userId"));
			    
			    
			// Exploratory sessions

		    } else if (command.equalsIgnoreCase("createExploratorySession")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createExploratorySession()");
			    output = CApiXStudioWeb_Campaign.createExploratorySession(session, request, request.getParameter("parentFolderId"), request.getParameter("assignedToId"), request.getParameter("sutId"));

		    } else if (command.equalsIgnoreCase("updateExploratorySessionDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionDetails()");
			    output = CApiXStudioWeb_Campaign.updateExploratorySessionDetails(session, request);



			    
		    } else if (command.equalsIgnoreCase("updateExploratorySessionStartDateTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionStartDateTime()");
			    output = CApiXStudioWeb_Campaign.updateExploratorySessionStartDateTime(session, request.getParameter("exploratorySessionId"));
			    
		    } else if (command.equalsIgnoreCase("updateExploratorySessionStopDateTime")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionStopDateTime()");
			    output = CApiXStudioWeb_Campaign.updateExploratorySessionStopDateTime(session, request.getParameter("exploratorySessionId"));
			    

			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateExploratorySessionsPriority")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionsPriority()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_EXPLORATORYSESSION, request.getParameter("exploratorySessionIds"), 3, request.getParameter("priority")); // integer

		    } else if (command.equalsIgnoreCase("updateExploratorySessionAssignedTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionAssignedTo()");
		        output = CApiXStudioWeb_Campaign.updateExploratorySessionAssignedTo(session, request.getParameter("exploratorySessionId"), request.getParameter("userId"));

		    } else if (command.equalsIgnoreCase("updateExploratorySessionsAssignedTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionsAssignedTo()");
		        output = CApiXStudioWeb_Campaign.updateExploratorySessionsAssignedTo(session, request.getParameter("exploratorySessionIds"), request.getParameter("userId"));

		        
		        
		        
		    } else if (command.equalsIgnoreCase("updateExploratorySessionSut")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateExploratorySessionSut()");
		        output = CApiXStudioWeb_Campaign.updateExploratorySessionSut(session, request.getParameter("exploratorySessionId"), request.getParameter("sutId"));

		    	
		    } else if (command.equalsIgnoreCase("linkExploratorySessionToBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkExploratorySessionToBugs()");
			    output = CApiXStudioWeb_Campaign.linkExploratorySessionToBugs(session, request, request.getParameter("connectorIndex"), request.getParameter("exploratorySessionId"));

		        
		    } else if (command.equalsIgnoreCase("insertExploratorySessionAttributeValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertExploratorySessionAttributeValue()");
			    output = CApiXStudioWeb_Campaign.insertExploratorySessionAttributeValue(session, request.getParameter("exploratorySessionId"), request.getParameter("attributeType"), request.getParameter("attributeId"), request.getParameter("attributeValue"));

		    } else if (command.equalsIgnoreCase("removeExploratorySessionAttributeValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeExploratorySessionAttributeValue()");
			    output = CApiXStudioWeb_Campaign.removeExploratorySessionAttributeValue(session, request.getParameter("exploratorySessionId"), request.getParameter("attributeType"), request.getParameter("attributeId"));

		    } else if (command.equalsIgnoreCase("deleteExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteExploratorySessions()");
			    output = CApiXStudioWeb_Campaign.deleteExploratorySessions(session, request.getParameter("exploratorySessionIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteExploratorySessions()");
			    output = CApiXStudioWeb_Campaign.hardDeleteExploratorySessions(session, request.getParameter("exploratorySessionIds"));

		    } else if (command.equalsIgnoreCase("moveExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveExploratorySessions()");
			    output = CApiXStudioWeb_Campaign.moveExploratorySessions(session, request.getParameter("exploratorySessionIds"), request.getParameter("destParentFolderId"));

		    } else if (command.equalsIgnoreCase("copyExploratorySessions")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyExploratorySessions()");
			    output = CApiXStudioWeb_Campaign.copyExploratorySessions(session, request.getParameter("exploratorySessionIds"), request.getParameter("exploratorySessionNames"), request.getParameter("destParentFolderId"));
			

			    
			    
			    
			    
			// Bugs
			    
		    } else if (command.equalsIgnoreCase("createBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createBug()");
			    output = CApiXStudioWeb_Defect.createBug(session, request, request.getParameter("parentFolderId"), request.getParameter("assignedToIds"), request.getParameter("foundInIds"), request.getParameter("followerIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("createGenericBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createGenericBug()");
			    output = CApiXStudioWeb_Defect.createGenericBug(session, request, request.getParameter("parentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("createExternalBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createExternalBug()");
			    output = CApiXStudioWeb_Defect.createExternalBug(session, request, request.getParameter("connectorIndex"), request.getParameter("projectName"),
			    																   request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("instanceId"),                                     // either provide instanceId
			    																                                                                      request.getParameter("agentId"), request.getParameter("instanceIndex"), // or agentId + instanceIndex
							    												   request.getParameter("exploratorySessionId"));                                                                                             // or exploratorySessionId
			    
			    
		    } else if (command.equalsIgnoreCase("updateBugDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateBugDetails()");
			    output = CApiXStudioWeb_Defect.updateBugDetails(session, request);
			    
				
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateBugsStatus")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateBugsStatus()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_DEFECT, request.getParameter("bugIds"), 3, request.getParameter("status")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateBugsSeverity")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateBugsSeverity()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_DEFECT, request.getParameter("bugIds"), 4, request.getParameter("severity")); // integer
			    

		    } else if (command.equalsIgnoreCase("updateBugsPriority")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateBugsPriority()");
			    output = CApiXStudioWeb_Common.updateStandardField(session, CTreeNodeData.TYPE_DEFECT, request.getParameter("bugIds"), 5, request.getParameter("priority")); // integer
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("assignBugTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - assignBugTo()");
			    output = CApiXStudioWeb_Defect.assignBugTo(session, request.getParameter("bugId"), request.getParameter("userIds"));
			    
		    } else if (command.equalsIgnoreCase("assignBugsTo")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - assignBugsTo()");
			    output = CApiXStudioWeb_Defect.assignBugsTo(session, request.getParameter("bugIds"), request.getParameter("userId"));

			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("setBugFoundIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - setBugFoundIn()");
			    output = CApiXStudioWeb_Defect.setBugFoundIn(session, request.getParameter("bugId"), request.getParameter("sutIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("setBugFixedIn")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - setBugFixedIn()");
			    output = CApiXStudioWeb_Defect.setBugFixedIn(session, request.getParameter("bugId"), request.getParameter("sutIds"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("updateBugsFollowers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateBugsFollowers()");
			    output = CApiXStudioWeb_Defect.updateBugsFollowers(session, request.getParameter("bugIds"), request.getParameter("userIds"));
			    
		    } else if (command.equalsIgnoreCase("addBugsFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - addBugsFollower()");
			    output = CApiXStudioWeb_Defect.addBugsFollower(session, request.getParameter("bugIds"), request.getParameter("followerId"));
			    
		    } else if (command.equalsIgnoreCase("removeBugsFollower")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - removeBugsFollower()");
			    output = CApiXStudioWeb_Defect.removeBugsFollower(session, request.getParameter("bugIds"), request.getParameter("followerId"));
			    
			    
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("linkBugsToTestExecution")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - linkBugsToTestExecution()");
			    output = CApiXStudioWeb_Defect.linkBugsToTestExecution(session, request, IConstants.FIRST_CONNECTOR_INDEX, request.getParameter("sessionId"), request.getParameter("testId"), request.getParameter("instanceId"));
			    
			    
			    
			    
		    } else if (command.equalsIgnoreCase("insertBugCustomFieldValue")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertBugCustomFieldValue()");
			    output = CApiXStudioWeb_Defect.insertBugCustomFieldValue(session, request.getParameter("bugId"), request.getParameter("customFieldType"), request.getParameter("customFieldId"), request.getParameter("customFieldValue"));
			    
			    
		    } else if (command.equalsIgnoreCase("freezeBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeBug()");
		        output = CApiXStudioWeb_Defect.freezeBug(session, request.getParameter("bugId"));
		    } else if (command.equalsIgnoreCase("unfreezeBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeBug()");
		        output = CApiXStudioWeb_Defect.unfreezeBug(session, request.getParameter("bugId"));
		    } else if (command.equalsIgnoreCase("signBug")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signBug()");
		        output = CApiXStudioWeb_Defect.signBug(session, request.getParameter("bugId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyBugSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyBugSignature()");
		        output = CApiXStudioWeb_Defect.verifyBugSignature(session, request.getParameter("bugId"));

	
		    } else if (command.equalsIgnoreCase("deleteBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteBugs()");
			    output = CApiXStudioWeb_Defect.deleteBugs(session, request.getParameter("bugIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteBugs()");
			    output = CApiXStudioWeb_Defect.hardDeleteBugs(session, request.getParameter("bugIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteGenericBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteGenericBugs()");
			    output = CApiXStudioWeb_Defect.deleteGenericBugs(session, request.getParameter("bugIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveBugs()");
			    output = CApiXStudioWeb_Defect.moveBugs(session, request.getParameter("bugIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveGenericBugs")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveGenericBugs()");
			    output = CApiXStudioWeb_Defect.moveGenericBugs(session, request.getParameter("bugIds"), request.getParameter("destParentFolderId"));
			    
			    

			// Profiles
			    
		    } else if (command.equalsIgnoreCase("createProfile")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createProfile()");
			    output = CApiXStudioWeb_User.createProfile(session, request, request.getParameter("profileName"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateProfile")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateProfile()");
			    output = CApiXStudioWeb_User.updateProfile(session, request, request.getParameter("profileId"), request.getParameter("profileName"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteProfile")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateProfile()");
			    output = CApiXStudioWeb_User.deleteProfile(session, request.getParameter("profileId"));
			    
			    


			// Users
			    
		    } else if (command.equalsIgnoreCase("createUser")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createUser()");
			    output = CApiXStudioWeb_User.createUser(session, request, request.getParameter("parentFolderId"));
			    

		    } else if (command.equalsIgnoreCase("updateUserDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateUserDetails()");
			    output = CApiXStudioWeb_User.updateUser(session, request);
			    

		    } else if (command.equalsIgnoreCase("insertUserRoles")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - insertUserRoles()");
			    output = CApiXStudioWeb_User.insertUserRoles(session, request.getParameter("userIds"), request.getParameter("profileId"), request.getParameter("teamId"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteUserRole")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteUserRole()");
			    output = CApiXStudioWeb_User.deleteUserRole(session, request.getParameter("userId"), request.getParameter("profileId"), request.getParameter("teamId"));
			    
			
		    } else if (command.equalsIgnoreCase("deleteUserRoles")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteUserRoles()");
			    output = CApiXStudioWeb_User.deleteUserRoles(session, request.getParameter("userId"));
			    
			

		    } else if (command.equalsIgnoreCase("deleteUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteUsers()");
			    output = CApiXStudioWeb_User.deleteUsers(session, request.getParameter("userIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveUsers()");
			    output = CApiXStudioWeb_User.moveUsers(session, request.getParameter("userIds"), request.getParameter("destParentFolderId"));
			    
		    } else if (command.equalsIgnoreCase("importLDAPUsers")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - importLDAPUsers()");
			    output = CApiXStudioWeb_User.importLDAPUsers(session, request, request.getParameter("parentFolderId"), request.getParameter("profileId"), request.getParameter("teamId"));
			    


			    
			 // Teams
			    
		    } else if (command.equalsIgnoreCase("createTeam")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createTeam()");
			    output = CApiXStudioWeb_User.createUserGroup(session, request, request.getParameter("parentFolderId"));
			    

		    } else if (command.equalsIgnoreCase("updateTeamDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTeamDetails()");
			    output = CApiXStudioWeb_User.updateUserGroup(session, request);
			    
				
			    
			    
		    } else if (command.equalsIgnoreCase("updateTeamAccessRights")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTeamAccessRights()");
			    output = CApiXStudioWeb_User.updateUserGroupAccessRights(session, request, request.getParameter("teamId"));
			    
			
		    } else if (command.equalsIgnoreCase("updateTeamAccessRightsRequirementConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTeamAccessRightsRequirementConnector()");
			    output = CApiXStudioWeb_User.updateUserGroupAccessRightsRequirementConnector(session, request, request.getParameter("teamId"), request.getParameter("connectorIndex"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateTeamAccessRightsBugConnector")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTeamAccessRightsBugConnector()");
			    output = CApiXStudioWeb_User.updateUserGroupAccessRightsBugConnector(session, request, request.getParameter("teamId"), request.getParameter("connectorIndex"));
			    
			    
			    
			    

		    } else if (command.equalsIgnoreCase("updateFilteredTeams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateFilteredTeams()");
			    output = CApiXStudioWeb_User.updateFilteredTeams(session, request.getParameter("teamIds"));
			    

		    } else if (command.equalsIgnoreCase("deleteTeams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteTeams()");
			    output = CApiXStudioWeb_User.deleteUserGroups(session, request.getParameter("teamIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveTeams")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveTeams()");
			    output = CApiXStudioWeb_User.moveUserGroups(session, request.getParameter("teamIds"), request.getParameter("destParentFolderId"));
			    
	
			    
			    
			    
			// Asset
			

		    } else if (command.equalsIgnoreCase("createAsset")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createAsset()");
			    output = CApiXStudioWeb_Asset.createAsset(session, request, request.getParameter("parentFolderId"));
			    

		    } else if (command.equalsIgnoreCase("moveAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveAssets()");
			    output = CApiXStudioWeb_Asset.moveAssets(session, request.getParameter("assetIds"), request.getParameter("destParentFolderId"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteAssets()");
		        output = CApiXStudioWeb_Asset.deleteAssets(session, request.getParameter("assetIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteAssets")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteAssets()");
		        output = CApiXStudioWeb_Asset.hardDeleteAssets(session, request.getParameter("assetIds"));
			    
			
		    } else if (command.equalsIgnoreCase("updateAssetDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAssetDetails()");
			    output = CApiXStudioWeb_Asset.updateAssetDetails(session, request);
			    
			
			    
			// Reusable Testcases

		    } else if (command.equalsIgnoreCase("createReusableTestcase")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createReusableTestcase()");
			    output = CApiXStudioWeb_Asset.createReusableTestcase(session, request, request.getParameter("parentFolderId"));
			    
			
		    } else if (command.equalsIgnoreCase("copyReusableTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - copyReusableTestcases()");
			    output = CApiXStudioWeb_Asset.copyReusableTestcases(session, request.getParameter("reusableTestcaseIds"), request.getParameter("reusableTestcaseNames"), request.getParameter("destParentFolderId"));
			    
			
		    } else if (command.equalsIgnoreCase("moveReusableTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveReusableTestcases()");
			    output = CApiXStudioWeb_Asset.moveReusableTestcases(session, request.getParameter("reusableTestcaseIds"), request.getParameter("destParentFolderId"));
			    
	
		    } else if (command.equalsIgnoreCase("deleteReusableTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteReusableTestcases()");
		        output = CApiXStudioWeb_Asset.deleteReusableTestcases(session, request.getParameter("reusableTestcaseIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteReusableTestcases")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteReusableTestcases()");
		        output = CApiXStudioWeb_Asset.hardDeleteReusableTestcases(session, request.getParameter("reusableTestcaseIds"));
			    

		    } else if (command.equalsIgnoreCase("updateReusableTestcaseDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateReusableTestcaseDetails()");
			    output = CApiXStudioWeb_Asset.updateReusableTestcaseDetails(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("updateReusableTestcaseProcedure")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateReusableTestcaseProcedure()");
			    output = CApiXStudioWeb_Asset.updateReusableTestcaseProcedure(session, request, request.getParameter("reusableTestcaseId"));
			    
			    
			    
			    
			// Agent
			/*
		    } else if (command.equalsIgnoreCase("createAgent")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createAgent()");
			    output = CApiXStudioWeb_Agent.createAgent(session, request, request.getParameter("parentFolderId"));
			    
			*/
		    } else if (command.equalsIgnoreCase("createAgentAuto")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  GET - createAgentAuto()");
		        output = CApiXStudioWeb_Agent.createAgentAuto(session, request.getParameter("name"), request.getParameter("os"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateAgentDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAgentDetails()");
			    output = CApiXStudioWeb_Agent.updateAgent(session, request);
			    

		    } else if (command.equalsIgnoreCase("deleteAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteAgents()");
			    output = CApiXStudioWeb_Agent.deleteAgents(session, request.getParameter("agentIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteAgents()");
			    output = CApiXStudioWeb_Agent.hardDeleteAgents(session, request.getParameter("agentIds"));
			    
			    
		    } else if (command.equalsIgnoreCase("moveAgents")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveAgents()");
			    output = CApiXStudioWeb_Agent.moveAgents(session, request.getParameter("agentIds"), request.getParameter("destParentFolderId"));
			    

		    } else if (command.equalsIgnoreCase("updateAgentLastKeepalive")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAgentLastKeepalive()");
			    output = CApiXStudioWeb_Agent.updateAgentLastKeepalive(session, request.getParameter("agentId"), request.getParameter("lastKeepaliveDateTime"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateAgentLastWork")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateAgentLastWork()");
			    output = CApiXStudioWeb_Agent.updateAgentLastWork(session, request.getParameter("agentId"), request.getParameter("lastWorkDateTime"));
			    
			    
			    
			// SQL reports
		    } else if (command.equalsIgnoreCase("createSqlReport")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createSqlReport()");
			    output = CApiXStudioWeb_SqlReport.createSqlReport(session, request, request.getParameter("parentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("updateSqlReportDetails")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateSqlReportDetails()");
			    output = CApiXStudioWeb_SqlReport.updateSqlReport(session, request);
			    
			    
		    } else if (command.equalsIgnoreCase("moveSqlReports")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveSqlReports()");
			    output = CApiXStudioWeb_SqlReport.moveSqlReports(session, request.getParameter("sqlReportIds"), request.getParameter("destParentFolderId"));
			    
			    
		    } else if (command.equalsIgnoreCase("deleteSqlReports")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteSqlReports()");
		        output = CApiXStudioWeb_SqlReport.deleteSqlReports(session, request.getParameter("sqlReportIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteSqlReports")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteSqlReports()");
		        output = CApiXStudioWeb_SqlReport.hardDeleteSqlReports(session, request.getParameter("sqlReportIds"));
			    

			    
			    
		    // ----------------------------------------------------------------------------------------------------------------------------------------
			// Documents
			// ----------------------------------------------------------------------------------------------------------------------------------------

			    
		    } else if (command.equalsIgnoreCase("moveDocuments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - moveDocuments()");
			    output = CApiXStudioWeb_Document.moveDocuments(session, request.getParameter("documentIds"), request.getParameter("destParentFolderId"));
			    
		    } else if (command.equalsIgnoreCase("freezeDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - freezeDocument()");
		        output = CApiXStudioWeb_Document.freezeDocument(session, request.getParameter("documentId"));
		    } else if (command.equalsIgnoreCase("unfreezeDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unfreezeDocument()");
		        output = CApiXStudioWeb_Document.unfreezeDocument(session, request.getParameter("documentId"));
		    } else if (command.equalsIgnoreCase("signDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - signDocument()");
		        output = CApiXStudioWeb_Document.signDocument(session, request.getParameter("documentId"), request.getParameter("textToken"), request.getParameter("consent"));
		    } else if (command.equalsIgnoreCase("verifyDocumentSignature")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - verifyDocumentSignature()");
		        output = CApiXStudioWeb_Document.verifyDocumentSignature(session, request.getParameter("documentId"));
			    
		    } else if (command.equalsIgnoreCase("deleteDocuments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteDocuments()");
		        output = CApiXStudioWeb_Document.deleteDocuments(session, request.getParameter("documentIds"));
		    } else if (command.equalsIgnoreCase("hardDeleteDocuments")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - hardDeleteDocuments()");
		        output = CApiXStudioWeb_Document.hardDeleteDocuments(session, request.getParameter("documentIds"));

		        
		        
		    } else if (command.equalsIgnoreCase("uploadDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - uploadDocument()");
			    output = CApiXStudioWeb_Document.uploadDocument(session, request, request.getParameter("documentName"), request.getParameter("folderId"));

		    } else if (command.equalsIgnoreCase("updateDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateDocument()");
			    output = CApiXStudioWeb_Document.updateDocument(session, request, request.getParameter("documentId"), request.getParameter("documentName"));

		    

		    } else if (command.equalsIgnoreCase("lockDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - lockDocument()");
		        output = CApiXStudioWeb_Document.lockDocument(session, request, request.getParameter("documentId"));

		    } else if (command.equalsIgnoreCase("unlockDocument")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - unlockDocument()");
		        output = CApiXStudioWeb_Document.unlockDocument(session, request, request.getParameter("documentId"));

			    
		        
			    
			    
		    // ----------------------------------------------------------------------------------------------------------------------------------------
			// Gherkin
			// ----------------------------------------------------------------------------------------------------------------------------------------

			    
		    } else if (command.equalsIgnoreCase("createStatement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - createStatement()");
		        output = CApiXStudioWeb_Gherkin.createStatement(session, request.getParameter("statementType"), request.getParameter("statementDescription"), request.getParameter("statementHelp"));
			    

			    
		    } else if (command.equalsIgnoreCase("updateStatement")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateStatement()");
		        output = CApiXStudioWeb_Gherkin.updateStatement(session, request.getParameter("statementId"), request.getParameter("statementType"), request.getParameter("statementDescription"), request.getParameter("statementHelp"));
			    

			    
		    } else if (command.equalsIgnoreCase("updateTestStatements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - updateTestStatements()");
		        output = CApiXStudioWeb_Gherkin.updateTestStatements(session, request.getParameter("testId"), request.getParameter("statementIds"));
			    

		    } else if (command.equalsIgnoreCase("deleteStatements")) {
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "--------------------------------------------------------------------------------------");
		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==>  POST - deleteStatement()");
		        output = CApiXStudioWeb_Gherkin.deleteStatements(session, request.getParameter("statementIds"));
			    

		    } else {
		    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "unknown command: " + command + "!");
		    }
		    
		} catch (Exception e) {
			e.printStackTrace();
			output = CRestUtils.formatCrash("Unexpected error!");
		}
	    
	    if (output.length() > CATALINA_LOG_MAX_SIZE && CStudioGlobals.traceLevel != LOG_PRIORITY_FINE)
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> POST " + command + "() returning " + CTextUtils.left(output, CATALINA_LOG_MAX_SIZE) + "...");
	    else
	    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> POST " + command + "() returning " + output);

	    
	    updateStatusResponse(response, output); // set an status=401 or 500 in case of errors/exception/crash
       	
	    PrintWriter out = response.getWriter();

		/*
	    out.println("command          = " + request.getMethod() + "\n");
	    out.println("ServletUri       = " + request.getRequestURI() + "\n");
	    out.println("ContextPath      = " + request.getContextPath() + "\n");
	    out.println("ServletPath      = " + request.getServletPath() + "\n");
	    out.println("PathInfo         = " + request.getPathInfo() + "\n");
	    out.println("Nom du serveur   = " + request.getServerName() + "\n");
	    out.println("Logiciel utilisé = " + request.getServletContext().getServerInfo() + "\n"); 
	    out.println("Port du serveur  = " + request.getServerPort() + "\n");
	    out.println("Path translated  = " + request.getPathTranslated() + "\n");
		*/
	    
	    if (output == null)
	    	out.println(CRestUtils.formatError("null returned by server"));
	    
	    out.println(CTextUtils.replaceInvisibleCharactersInJson(output));
	}
	
	
	@Override
	public void init() throws ServletException {
		super.init();
		CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "init()");
		
    	ServletContext servletContext = null;
    	try {
			servletContext = getServletContext();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "servletContext: " + servletContext);
		} catch (Exception e1) {
			e1.printStackTrace();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER BUT IN A MOCKED UP TEST ENVIRONMENT!");
		}
    	
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "==================================================================");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "starting REST API server v" + IConstants.XSTUDIO_APPLICATION_VERSION);
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "==================================================================");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "constructing main...");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "java.library.path: " + System.getProperty("java.library.path"));
		try {
			//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "current location : " + CUtils.getCurrentLocation());
		} catch (Exception e) {
			e.printStackTrace();
		}
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "user home dir    : " + System.getProperty("user.home"));
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "user run. proc.  : " + System.getProperty("user.name"));
		
		CNetworkInfo networkInfo = new CNetworkInfo();
		CStudioGlobals.osType = networkInfo.getOsType();
		CStudioGlobals.jnlpMode = false;
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Mode             : Standard");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "OS type          : " + CStudioGlobals.osType);

        // it's set only in loadApplication() !
		// CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "xstudioWebBaseUrl: " + CStudioGlobals.xstudioWebBaseURL);

		try {
			this.SERVLET_VERSION = servletContext.getMajorVersion() + "." + servletContext.getMinorVersion();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Servlet version: " + this.SERVLET_VERSION);
		} catch (Exception e2) {
		}
        
        // ----------------------------------------
        // Launched from XStudio
        // ----------------------------------------
        CStudioGlobals.launchedFromXStudio = false;

        // ----------------------------------------
        // Servlet path
        // ----------------------------------------
		/*
		Prior versions of Tomcat allowed getRealPath("xxxxx.xxx") with no initial separator at all, 
		but Tomcat 8 requires "/xxxxx.xxx" (otherwise returns null) !!!
		*/
		try {
			CStudioGlobals.servletWorkingDirectory = servletContext.getRealPath("/");
		} catch (Exception e1) { // when executed locally
			CStudioGlobals.servletWorkingDirectory = "C:\\_Y_\\XStudio";
		}
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Servlet work. dir: " + CStudioGlobals.servletWorkingDirectory);

	    
        // ------------------------------------------
        // Ensure the Recycle bin folders are created
        // ------------------------------------------


		CStudioGlobals.SUT_RECYCLE_BIN_ID            = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_SUT,			"SUTs");
		CStudioGlobals.REQUIREMENT_RECYCLE_BIN_ID    = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_REQ,			"requirements");
		CStudioGlobals.SPECIFICATION_RECYCLE_BIN_ID  = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_SPEC,			"specifications");
		CStudioGlobals.TEST_RECYCLE_BIN_ID           = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_TEST,			"tests");
		CStudioGlobals.CAMPAIGN_RECYCLE_BIN_ID       = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_CAMPAIGN,		"campaigns");
		CStudioGlobals.DEFECT_RECYCLE_BIN_ID         = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_DEFECT,		"defects");
		CStudioGlobals.USER_RECYCLE_BIN_ID           = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_USER,			"users");
		CStudioGlobals.ASSET_RECYCLE_BIN_ID          = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_ASSET,		"assets");
		CStudioGlobals.AGENT_RECYCLE_BIN_ID          = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_AGENT,		"agents");
		CStudioGlobals.DOCUMENT_RECYCLE_BIN_ID       = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_DOCUMENT,		"documents");
		CStudioGlobals.SQLREPORT_RECYCLE_BIN_ID      = CApiXStudioWeb_Common.getRecycleBinId(ITreeConstants.PARENT_ROOT_FOLDER_ID_SQLREPORT,	"sql reports");
		
		CStudioGlobals.FAKE_TEST_RECYCLE_BIN_ID       = CApiXStudioWeb_Common.getRecycleBinFakeTestId();
		CStudioGlobals.FAKE_CAMPAIGN_RECYCLE_BIN_ID   = CApiXStudioWeb_Common.getRecycleBinFakeCampaignId();
	    
		// ----------------------------------------
		// XStudio.conf
		// ----------------------------------------
		String applicationConfPropertiesPath = null;
		try {
			applicationConfPropertiesPath = CStudioGlobals.servletWorkingDirectory + "/" + APPLICATION_CONF_PROPERTIES_FILENAME;
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "xstudio.conf: " + applicationConfPropertiesPath);
		} catch (Exception e) {
			applicationConfPropertiesPath = (new File(APPLICATION_CONF_PROPERTIES_FILENAME)).getAbsolutePath();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER: APPLICATION CONF: " + applicationConfPropertiesPath);
		}
		
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Application configuration properties: " + applicationConfPropertiesPath);
		
		/*
		// used when editing the server settings
		CStudioGlobals.originalApplicationConfProperties = new CApplicationConfProperties(applicationConfPropertiesPath); 
		// now that we've loaded the properties, default values are present in currentApplicationConfProperties but NOT in properties
		CStudioGlobals.originalApplicationConfProperties.updatePropertiesFromVariables();
		*/
		
		CStudioGlobals.applicationConfProperties = new CApplicationConfProperties(applicationConfPropertiesPath);
		// now that we've loaded the properties, default values are present in currentApplicationConfProperties but NOT in properties
		CStudioGlobals.applicationConfProperties.updatePropertiesFromVariables();

        // shortcuts
        CStudioGlobals.databaseType = IConstantsSql.DATABASE_MYSQL;
        
        if (CStudioGlobals.applicationConfProperties.getDatabaseType().equals(CApplicationConfProperties.DATABASE_TYPE_ORACLE))
        	CStudioGlobals.databaseType = IConstantsSql.DATABASE_ORACLE;
        
        else if (CStudioGlobals.applicationConfProperties.getDatabaseType().equals(CApplicationConfProperties.DATABASE_TYPE_MSSQL))
        	CStudioGlobals.databaseType = IConstantsSql.DATABASE_MSSQL;
        
        else if (CStudioGlobals.applicationConfProperties.getDatabaseType().equals(CApplicationConfProperties.DATABASE_TYPE_MARIADB))
        	CStudioGlobals.databaseType = IConstantsSql.DATABASE_MARIADB;
        
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Database type: " + CStudioGlobals.databaseType + " (1=MySql, 2=Oracle, 3=SQLServer, 4=MariaDB)");
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "*** If the service stops there it is because you're using a wrong version of Java (only Java 7, 8, or 9 are supported)!");

		// set the mail factory
		String smtpHost             = CStudioGlobals.applicationConfProperties.getSmtpHost();
		String smtpPort             = CStudioGlobals.applicationConfProperties.getSmtpPort();
		String smtpUsername         = CStudioGlobals.applicationConfProperties.getSmtpUsername();
		String smtpPassword         = CStudioGlobals.applicationConfProperties.getSmtpPassword();
		String smtpFrom             = CStudioGlobals.applicationConfProperties.getSmtpFrom();
		String smtpSecureConnection = CStudioGlobals.applicationConfProperties.getSmtpSecureConnection();

		CStudioGlobals.mailFactory = new CMailFactory(null, smtpHost, smtpPort, smtpFrom, // the from address MUST be on a valid domain name
				                                      smtpUsername, smtpPassword,
				                                      smtpSecureConnection.equals("SSL"), smtpSecureConnection.equals("TLS"),
        		                                      CStudioGlobals.traceLevel >= LOG_PRIORITY_FINE);

        System.out.println(CStudioGlobals.mailFactory); // we already have set a mail factory in Agent, no need to create a second on is XStudioGlobals
		
		CStudioGlobals.launchersVector = CStudioUtils.getAllPhysicalLauncherNames();
		System.out.println("=================================================================");
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Available launchers: " + CStudioGlobals.launchersVector);
		System.out.println("=================================================================");
		
		// ----------------------------------------
		// requirement.conf
		// ----------------------------------------
		String requirementConfPropertiesPath = null;
		try {
			requirementConfPropertiesPath = CStudioGlobals.servletWorkingDirectory + "/" + REQUIREMENT_CONF_PROPERTIES_FILENAME;
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "requirement.conf: " + requirementConfPropertiesPath);
		} catch (Exception e) {
			requirementConfPropertiesPath = (new File(REQUIREMENT_CONF_PROPERTIES_FILENAME)).getAbsolutePath();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER: REQUIREMENT CONF: " + requirementConfPropertiesPath);
		}

		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Requirement configuration properties: " + requirementConfPropertiesPath);
		CStudioGlobals.requirementConfProperties = new CRequirementConfProperties(requirementConfPropertiesPath);
		
		// now that we've loaded the properties, default values are present in currentApplicationConfProperties but NOT in properties
		CStudioGlobals.requirementConfProperties.updatePropertiesFromVariables();

		// ----------------------------------------
		// bugtracking.conf
		// ----------------------------------------
		String bugTrackingConfPropertiesPath = null;
		try {
			bugTrackingConfPropertiesPath = CStudioGlobals.servletWorkingDirectory + "/" + BUGTRACKING_CONF_PROPERTIES_FILENAME;
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "bugtracking.conf: " + bugTrackingConfPropertiesPath);
		} catch (Exception e) {
			bugTrackingConfPropertiesPath = (new File(BUGTRACKING_CONF_PROPERTIES_FILENAME)).getAbsolutePath();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER: BUGTRACKING CONF: " + bugTrackingConfPropertiesPath);
		}

		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Bug-tracking configuration properties: " + bugTrackingConfPropertiesPath);
		CStudioGlobals.bugTrackingConfProperties = new CBugTrackingConfProperties(bugTrackingConfPropertiesPath);

		// now that we've loaded the properties, default values are present in currentApplicationConfProperties but NOT in properties
		CStudioGlobals.bugTrackingConfProperties.updatePropertiesFromVariables();
		
		// ----------------------------------------
		// sso.conf
		// ----------------------------------------
		String ssoConfPropertiesPath = null;
		try {
			ssoConfPropertiesPath = CStudioGlobals.servletWorkingDirectory + "/" + SSO_CONF_PROPERTIES_FILENAME;
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "sso.conf: " + ssoConfPropertiesPath);
		} catch (Exception e) {
			ssoConfPropertiesPath = (new File(SSO_CONF_PROPERTIES_FILENAME)).getAbsolutePath();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER: SSO CONF: " + ssoConfPropertiesPath);
		}

		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "SSO configuration properties: " + ssoConfPropertiesPath);
		CStudioGlobals.ssoConfProperties = new CSsoConfProperties(ssoConfPropertiesPath);

		// now that we've loaded the properties, default values are present in currentApplicationConfProperties but NOT in properties
		CStudioGlobals.ssoConfProperties.updatePropertiesFromVariables();
		
		// ----------------------------------------
		// Plugin.conf
		// ----------------------------------------
		String pluginConfPropertiesPath = null;
		try {
			pluginConfPropertiesPath = CStudioGlobals.servletWorkingDirectory + "/" + PLUGIN_CONF_PROPERTIES_FILENAME;
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "plugin.conf: " + pluginConfPropertiesPath);
		} catch (Exception e) {
			pluginConfPropertiesPath = (new File(PLUGIN_CONF_PROPERTIES_FILENAME)).getAbsolutePath();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NOT RUNNING ON A REAL SERVER: PLUGIN CONF: " + pluginConfPropertiesPath);
		}

		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Plugin configuration properties: " + pluginConfPropertiesPath);
		CStudioGlobals.pluginConfProperties = new CPluginConfProperties(pluginConfPropertiesPath);
    
        // ---------------------------------------------------------------------------------------------------------------
        // prepare the temporary keystore that will include all the secure server's certificates we may want to connect to.
        // we can do this now as they are only server settings (cannot update certificates from user preferences)
        // ---------------------------------------------------------------------------------------------------------------
        CStudioUtils.createTemporaryKeyStore(); // i.e. /var/cache/tomcat8/temp/.xstudio_ks_20709657232
        CStudioUtils.importAllCertificatesInKeyStore();
        
        // ----------------------
        // Check conn. settings
        // ----------------------
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, ">>> checking application settings...");
        checkApplicationSettings(servletContext);
        
        // ----------------------
        //  Update the verbosity
        // ----------------------
        //LOG_PRIORITY = LOG_PRIORITY_SEVERE;
	}
	
    private static void checkApplicationSettings(ServletContext servletContext) {
        short connectionResult = CStudioSqlConnector.connectToXStudioDB(servletContext);
        if (connectionResult == CStudioSqlConnector.CONNECTION_SUCCESS) {
        	// the API server need those additional engines
	        CStudioSqlFactory.setSqlTestplanEngine(     new CStudioSqlTestplanEngine());
	        CStudioSqlFactory.setSqlSpecificationEngine(new CStudioSqlSpecificationEngine());
	        CStudioSqlFactory.setSqlTaskEngine(         new CStudioSqlTaskEngine());
	        CStudioSqlFactory.setSqlRequirementEngine(  new CStudioSqlRequirementEngine());
	        CStudioSqlFactory.setSqlDefectEngine(       new CStudioSqlDefectEngine());
	        CStudioSqlFactory.setSqlTransactionalEngine(new CStudioSqlTransactionalEngine());
	        
            CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, ">>> checking requirement settings...");
            checkRequirementSettings(servletContext);
        } else {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Wrong application settings");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Please fix the problems in the configuration and restart your servlet container...");
        	//System.exit(1001); // tomcat must continue to run as it's now also hosting XStudio legacy and it will be necessary for instance to update the schema 
        }
    }

    private static void checkRequirementSettings(ServletContext servletContext) {
        short connectionResult = CStudioSqlConnector.connectToRequirementDBs();
        if (connectionResult == CStudioSqlConnector.CONNECTION_SUCCESS) {
            checkBugTrackingSettings(servletContext);
        } else {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+---------------------- WARNING ----------------------------------------------+");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Wrong requirements settings!                                                |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Tomcat is NOT stopped because it would prevent XStudio to connect           |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| in JNLP mode BUT XStudio.web MAY NOT be available !!!                       |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Please fix the requirement settings.                                        |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| NOTE: This may be normal if you're using user-preferences overriding!       |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|       In this case, the settings will be checked again after authentication |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+-----------------------------------------------------------------------------+");
        	//System.exit(1002); // tomcat must continue to run as it's now also hosting XStudio legacy
        	//ANYWAY, we need to continue checking bug-tracking settings for the app to work properly
        	checkBugTrackingSettings(servletContext);
        }
    }

    private static void checkBugTrackingSettings(ServletContext servletContext) {
        short connectionResult = CStudioSqlConnector.connectToBugTrackingDBs(servletContext);
        if (connectionResult == CStudioSqlConnector.CONNECTION_SUCCESS) {
            if (CStudioGlobals.bugTrackingConfProperties.nbConnectors == 1 && CStudioGlobals.bugTrackingConfProperties.bugTrackingSelection[0].equalsIgnoreCase(BUGTRACKING_NAME_NONE)) {
            	CStudioGlobals.bugTrackingActivated = false;
            } else {
            	CStudioGlobals.bugTrackingActivated = true;
            }
            
            // updating the trace level from what we have in the application conf property file
	        CStudioGlobals.setTraceLevel(CStudioGlobals.applicationConfProperties.getServletTraceLevel());
	        
            loadApplication(servletContext);
        } else {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+---------------------- WARNING ----------------------------------------------+");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Wrong bug-tracking settings!                                                |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Tomcat is NOT stopped because it would prevent XStudio to connect           |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| in JNLP mode BUT XStudio.web MAY NOT be available !!!                       |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| Please fix the bug-tracking settings.                                       |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|                                                                             |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| NOTE: This may be normal if you're using user-preferences overriding!       |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "|       In this case, the settings will be checked again after authentication |");
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+-----------------------------------------------------------------------------+");
        	//System.exit(1003); // tomcat must continue to run as it's now also hosting XStudio legacy
        	//ANYWAY, we need to load the different global hashtables (timetags, etc.) for the app to work properly

        	// updating the trace level from what we have in the application conf property file
        	CStudioGlobals.setTraceLevel(CStudioGlobals.applicationConfProperties.getServletTraceLevel());
        
        	loadApplication(servletContext);
        }
    }
    
    private static void loadApplication(ServletContext servletContext) {
		String restApiBaseUrl = CStudioGlobals.applicationConfProperties.getRestApiBaseUrl();   // https://my_server/xqual/api
		CStudioGlobals.xstudioWebBaseURL = restApiBaseUrl.replaceFirst("/api", "/xstudio.web"); // https://my_server/xqual/xstudio.web
		
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+-----------------------------------------------------------------------------+");
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "| XStudio.web base URL (xstudioWebBaseUrl): " + CStudioGlobals.xstudioWebBaseURL);
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "+-----------------------------------------------------------------------------+");
		
    	// empty the dedicated temporary folder (create it if does not exist)
    	CStudioUtils.clearTemporaryFolder();

       	// cache all the timetags available
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving timetags..." );
        String xmlContent = CStudioSqlFactory.getSqlGetEngine().getTimetagList();
        CStudioGlobals.timetagIdTimetagNameHashtable = CStudioUtils.computeIdNameTreeMap(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.timetagIdTimetagNameHashtable.toString());
        CStudioGlobals.timetagNameTimetagIdHashtable = CStudioUtils.computeNameIdTreeMap(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.timetagNameTimetagIdHashtable.toString());
        
        
    	// we load these with to-be-localized terms
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving requirement types hashtable...");
        CStudioSqlFactory.getSqlGetEngine().getRequirementTypeHashtables_API();
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- requirementTypeIdsOrderedVector              : " + CStudioGlobals.requirementTypeIdsOrderedVector.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- requirementTypeIdRequirementTypeNameHashtable: " + CStudioGlobals.requirementTypeIdRequirementTypeNameHashtable.toString());

        // we load these with to-be-localized terms
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving test types hashtable...");
        CStudioSqlFactory.getSqlGetEngine().getTestTypeHashtables_API();
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- testTypeIdsOrderedVector: " + CStudioGlobals.testTypeIdsOrderedVector.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- testTypeIdTestTypeNameHashtable : " + CStudioGlobals.testTypeIdTestTypeNameHashtable.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- testTypeIdTestTypeAliasHashtable: " + CStudioGlobals.testTypeIdTestTypeAliasHashtable.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- testTypeIdTestTypeColorHashtable: " + CStudioGlobals.testTypeIdTestTypeColorHashtable.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- testTypeIdTestTypeIndexHashtable: " + CStudioGlobals.testTypeIdTestTypeIndexHashtable.toString());

        // we load these with to-be-localized terms
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving asset types hashtables..." );
        CStudioSqlFactory.getSqlGetEngine().getAssetTypeHashtables_API();
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- assetTypeIdAssetTypeNameHashtable : " + CStudioGlobals.assetTypeIdAssetTypeNameHashtable.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- assetTypeIdAssetTypeIconNameHashtable: " + CStudioGlobals.assetTypeIdAssetTypeIconNameHashtable.toString());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- assetTypeNameAssetTypeIdHashtable: " + CStudioGlobals.assetTypeNameAssetTypeIdHashtable.toString());
        
        
        
        
    	
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving userIdUserName hashtable...");
        CStudioGlobals.userIdUserNameHashtable = CStudioSqlFactory.getSqlGetEngine().getUserHashtable();
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.userIdUserNameHashtable.toString());

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving userIdCountryId hashtable..." );
        CStudioGlobals.userIdCountryIdHashtable = CStudioUtils.computeIdNameHashtable(CStudioSqlFactory.getSqlGetEngine().getUserIdCountryIdList());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.userIdCountryIdHashtable.toString());

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving userIdWeekendDays hashtable..." );
        CStudioGlobals.userIdWeekendDaysHashtable = CStudioUtils.computeIdVectorIntegerHashtable(CStudioSqlFactory.getSqlGetEngine().getUserIdWeekendDaysList());
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.userIdWeekendDaysHashtable.toString());

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving platform hashtables..." );
        xmlContent = CStudioSqlFactory.getSqlGetEngine().getPlatformList();
        CStudioGlobals.platformIdPlatformNameHashtable = CStudioUtils.computeIdNameHashtable(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.platformIdPlatformNameHashtable.toString());
        CStudioGlobals.platformNamePlatformIdHashtable = CStudioUtils.computeNameIdHashtable(xmlContent);

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving OS hashtables..." );
        xmlContent = CStudioSqlFactory.getSqlGetEngine().getOsList();
        CStudioGlobals.osIdOsNameHashtable = CStudioUtils.computeIdNameHashtable(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.osIdOsNameHashtable.toString());
        CStudioGlobals.osNameOsIdHashtable = CStudioUtils.computeNameIdHashtable(xmlContent);

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving country hashtable..." );
        xmlContent = CStudioSqlFactory.getSqlGetEngine().getCountryList();
        CStudioGlobals.countryIdCountryNameHashtable = CStudioUtils.computeStrIdNameHashtable(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.countryIdCountryNameHashtable.toString());
        CStudioGlobals.countryNameCountryIdHashtable = CStudioUtils.computeNameStrIdHashtable(xmlContent);

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving consent hashtable..." );
        xmlContent = CStudioSqlFactory.getSqlGetEngine().getConsentList();
        CStudioGlobals.consentIdConsentNameHashtable = CStudioUtils.computeStrIdNameHashtable(xmlContent);
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_MAIN, LOG_MAIN_LABEL, CStudioGlobals.consentIdConsentNameHashtable.toString());
        CStudioGlobals.consentNameConsentIdHashtable = CStudioUtils.computeNameStrIdHashtable(xmlContent);

        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "retrieving title hashtables..." );
        xmlContent = CStudioSqlFactory.getSqlGetEngine().getTitleList();
        CStudioGlobals.positionIdPositionNameHashtable = CStudioUtils.computeIdNameHashtable(xmlContent);
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, CStudioGlobals.positionIdPositionNameHashtable.toString());
        CStudioGlobals.positionNamePositionIdHashtable = CStudioUtils.computeNameIdHashtable(xmlContent);

        // store the current attachment storage type as a global variable
        if (CStudioGlobals.applicationConfProperties.getAttachmentsStorageType().equals(CApplicationConfProperties.ATTACHMENTS_STORAGE_TYPE_DB)) {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_MAIN, LOG_MAIN_LABEL, "Attachments are stored in Database");
        	CStudioGlobals.currentAttachmentsStorageType = CStudioAttachmentUtils.STORAGE_TYPE_DB;
       		
        } else if (CStudioGlobals.applicationConfProperties.getAttachmentsStorageType().equals(CApplicationConfProperties.ATTACHMENTS_STORAGE_TYPE_SMB)) {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_MAIN, LOG_MAIN_LABEL, "Attachments are stored in SMB storage");
        	CStudioGlobals.currentAttachmentsStorageType = CStudioAttachmentUtils.STORAGE_TYPE_SMB;
        	
			CStudioGlobals.smbFactory = new CSmbFactory(CStudioGlobals.applicationConfProperties.getAttachmentsFileSystemPath(), 
														CStudioGlobals.applicationConfProperties.getAttachmentsFileSystemUsername(), 
														CStudioGlobals.applicationConfProperties.getAttachmentsFileSystemPassword());

        } else if (CStudioGlobals.applicationConfProperties.getAttachmentsStorageType().equals(CApplicationConfProperties.ATTACHMENTS_STORAGE_TYPE_LOCAL)) {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_MAIN, LOG_MAIN_LABEL, "Attachments are stored in local storage");
        	CStudioGlobals.currentAttachmentsStorageType = CStudioAttachmentUtils.STORAGE_TYPE_LOCAL;

        	CStudioGlobals.localFileFactory = new CLocalFileFactory(CStudioGlobals.applicationConfProperties.getAttachmentsFileSystemPath());
       	}
        
        CStudioGlobals.connectorIndexRequirementIntegrated = (short)CStudioRequirementUtils.getRequirementTargetIndexIntegrated();
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "integrated requirements connector index: " + CStudioGlobals.connectorIndexRequirementIntegrated);
        CStudioGlobals.connectorIndexBugTrackingIntegrated = (short)CStudioDefectUtils.getBugTrackingTargetIndexIntegrated();
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "integrated bugtracking connector index: " + CStudioGlobals.connectorIndexBugTrackingIntegrated);

        // all the global variables are all global to all users so we cannot store the user rights etc.
        // and certain API call methods such as CStudioGlobals.isAuthorizedAction() so we need to set explicitly the rights
        CStudioGlobals.setCurrentUserRights(new CUserRights(CUserRights.ALL_RIGHTS));

        // too many problem trying to display stuff
        //CStudioGlobals.loadingPerTab = false;
        
        
        
        // wkhtmltoPdf not required anymore in the REST API
        //CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Installing PDF converter...");
    	//CStudioGlobals.wkHtmlToPdfFolder = CStudioUtils.installWkhtmltopdf(servletContext);
    	//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "wkhtmlToPdfFolder: " + CStudioGlobals.wkHtmlToPdfFolder);
    	
    	
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Installing HTML converter...");
    	CStudioGlobals.convertHtmlFolder = CStudioUtils.installConvertHtml(servletContext);
    	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "convertHtmlFolder: " + CStudioGlobals.convertHtmlFolder);
    	
    	
    	
    	
        CTraceUtils.trace(LOG_PRIORITY_WARNING, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "LOAD APPLICATION FINISHED");
        
        if (CStudioGlobals.applicationConfProperties.isCometIntegrationEnabled()) {
        	CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "------------------------------------------------------");
			// enable the SmarTesting Comet interactions
			// Settings up the connection to the Comet REST API server
			CCometRestConnectionManager connectionManager = new CCometRestConnectionManager(CStudioGlobals.applicationConfProperties.getCometRestApiUrl(),
																							CStudioGlobals.applicationConfProperties.getCometRestApiKey());
			CStudioGlobals.cometRestBackend = new CCometRestBackend(connectionManager);
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "------------------------------------------------------");
        }
        
        // +------------------------------+
        // |      CONNECTION CLEANER      |
        // +------------------------------+
        // check that the server does not maintain open free connection when it's not used by any client
        CTraceUtils.trace(LOG_PRIORITY_WARNING, CStudioGlobals.traceLevel, LOG_MAIN, LOG_MAIN_LABEL, "starting XStudio free connections thread with a timeout of 3 minutes...");
        threadFreeConnections = new CThreadFreeConnections(3*60*1000); // 3 minutes
        threadFreeConnections.start();
		
        // +------------------------------+
        // |         DAILY PROCESS        |
        // +------------------------------+
        CTraceUtils.trace(LOG_PRIORITY_WARNING, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "starting daily process...");
        int dailyProcessScheduleHour   = Integer.parseInt(CStudioGlobals.applicationConfProperties.getDailyProcessScheduleTime().split(":")[0]);
        int dailyProcessScheduleMinute = Integer.parseInt(CStudioGlobals.applicationConfProperties.getDailyProcessScheduleTime().split(":")[1]);
        dailyProcess = new CProcessDaily(dailyProcessScheduleHour, dailyProcessScheduleMinute, 0);
        dailyProcess.start();
         
        // +------------------------------+
        // |        WEEKLY PROCESS        |
        // +------------------------------+

        // +------------------------------+
        // |       MONTHLY PROCESS        |
        // +------------------------------+
        
    }    
    
    private void addHeadersToResponse(HttpServletResponse response) {
    	CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "adding headers (for cross-origin request sharing): API v" + API_VERSION);
		response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE, HEAD");
        response.addHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers, Authorization");
        //response.addHeader("Access-Control-Expose-Headers", "");
        response.addHeader("Access-Control-Max-Age", "1728000"); // 20 days
        //response.setHeader("Cache-control", "no-cache, no-store");
        //response.setHeader("Pragma", "no-cache");
        //response.setHeader("Expires", "-1");
    }

    private void updateStatusResponse(HttpServletResponse response, String jsonContent) {
    	if (jsonContent == null) {
    		CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "no response from the method!");
    		try {
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "no response from the method!");
			} catch (IOException e) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
    		return;
    	}
    	
    	String result = "success"; // if no result, it means we return data such as a form etc.
    	String message = "";
    	
    	JSONObject jsonObject;
		try {
			jsonObject = new JSONObject(jsonContent);
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "valid json content");
	    	try {result = jsonObject.getString("result");} catch (JSONException e) {CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "no result in json");}
	    	try {message = jsonObject.getString("message");} catch (JSONException e) {CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "no message in json");}
		} catch (JSONException e1) {
			//CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "invalid json content or array!");
		}
    	
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "result=" + result + "   message=" + message);

    	// don't use sendError() as this formats the error message in HTML etc.
    	
		if (result.equalsIgnoreCase("success")) {
			CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "SUCCESS: 200");
			response.setStatus(HttpServletResponse.SC_OK);

		} else if (result.equalsIgnoreCase("change_password")) {
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "CONTINUE: 100");
			response.setStatus(HttpServletResponse.SC_CONTINUE);
			
		} else if (result.equalsIgnoreCase("no-content")) {
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "NO-CONTENT: 204");
			response.setStatus(HttpServletResponse.SC_NO_CONTENT); // ERROR 204
		
		} else if (result.equalsIgnoreCase("bad-request")) {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "BAD-REQUEST: 400");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST); // ERROR 400

		} else if (result.equalsIgnoreCase("forbidden")) {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "FORBIDDEN: 403");
			response.setStatus(HttpServletResponse.SC_FORBIDDEN); // ERROR 403
		
		} else if (result.equalsIgnoreCase("failure")) {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "FAILURE: 404");
			response.setStatus(HttpServletResponse.SC_NOT_FOUND); // ERROR 404
		
		} else if (result.equalsIgnoreCase("crash")) {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "CRASH: 500");
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); // ERROR 500
		
		} else {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "UNDETERMINED: 500 / " + result);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); // ERROR 500
		}
    }

    private boolean isSessionTimedOut(HttpServletRequest request, HttpServletResponse response, boolean trackActivity) {
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "=======================================================");
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "checking if the session is valid or not...");
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- timeout setting            = " + CStudioGlobals.applicationConfProperties.getLogoutTimeout());
		
    	long TIMEOUT = CStudioGlobals.applicationConfProperties.getLogoutTimeout()*1000; // logout_timeout is in seconds
    	if (TIMEOUT == 0) {
    		TIMEOUT = Long.MAX_VALUE;
    	}
		CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- logout timeout (in millis) = " + TIMEOUT);
    	
    	HttpSession session = request.getSession(false); // if current session does not exist, then it does nothing.
		
		if (session == null || !request.isRequestedSessionIdValid()) {
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Session is null or invalidated (maybe because of a timeout)!");
			CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Please ensure you're authenticated.");
			return true;
			
		} else {
			Long lastAccessTime        = (Long)session.getAttribute("last_access_time");
			String currentUserId       = (String)session.getAttribute("user_id");
			CLocalization localization = (CLocalization)session.getAttribute("localization");

			CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- lastAccessTime             = " + lastAccessTime);
			CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- currentTimeMillis          = " + System.currentTimeMillis());
			CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- userId                     = " + currentUserId);
			CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- localization               = " + localization);
			
			if (lastAccessTime == null || currentUserId == null || localization == null) {
		        CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] User not authenticated");
		        CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> invalidating session...");
		        session.invalidate(); // no user logged in: invalidate session
		        synchronized(sessionVector) {
		        	sessionVector.remove(session);
		        }

		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] isSessionTimedOut() returning true (null attributes)...");
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "=======================================================");
			    return true;
		    
			} else if ((lastAccessTime != null && System.currentTimeMillis() - lastAccessTime > TIMEOUT)) {
		        CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] last access time=" + CCalendarUtils.millisTimeToDateString(lastAccessTime) + " ==> difference=" + CCalendarUtils.millisTimeToDurationString(System.currentTimeMillis() - lastAccessTime) + ": TIMEOUT!");
		        CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] ==> invalidating session...");
		        session.invalidate(); // timeout: invalidate session
		        synchronized(sessionVector) {
		        	sessionVector.remove(session);
		        }

		        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] isSessionTimedOut() returning true (timeout)...");
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "=======================================================");
				return true;
				
			} else {
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] last access time=" + CCalendarUtils.millisTimeToDateString(lastAccessTime) + " ==> difference=" + CCalendarUtils.millisTimeToDurationString(System.currentTimeMillis() - lastAccessTime) + ": OK");
		    	// reset the lastAccessTime
		    	if (trackActivity)
		    		session.setAttribute("last_access_time", System.currentTimeMillis());
		    	CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "[" + session.getId() + "] isSessionTimedOut() returning false...");
				CTraceUtils.trace(LOG_PRIORITY_FINE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "=======================================================");
		    	return false;
			}
		}
    }
    
	
	
	private String getCommand(HttpServletRequest request) {
		String command = request.getParameter("command");

		if (command == null || command.trim().length() == 0) {
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "no command parameter found, switchin to path-command...");
			//System.out.println("- - - - >>>>>> ServletUri      = " + request.getRequestURI() + "\n");
			//System.out.println("- - - - >>>>>> ContextPath     = " + request.getContextPath() + "\n");
			//System.out.println("- - - - >>>>>> ServletPath     = " + request.getServletPath() + "\n");
			//System.out.println("- - - - >>>>>> PathInfo        = " + request.getPathInfo() + "\n");
			//System.out.println("- - - - >>>>>> Path translated = " + request.getPathTranslated() + "\n");
			command = request.getPathInfo().substring(1); // to remove the first '/'
		}
		
		//CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "Command: " + command);
		return command;
	}
	
	private void cleanupIdempotencyStore() {
		// Use entrySet() and an Iterator to remove entries with 2 hours old entries
	    Iterator<ConcurrentMap.Entry<String, Long>> idempotencyStoreIterator = idempotencyStore.entrySet().iterator();
	    while (idempotencyStoreIterator.hasNext()) {
	        Map.Entry<String, Long> entry = idempotencyStoreIterator.next();
	        if (entry.getValue() < (CCalendarUtils.getCurrentTimeMillis() - 2*60*60*1000)) { // 2 hours old
	        	System.out.println("idempotencyStore size = " + idempotencyStore.size());
	        	System.out.println("clearing requestId " + entry.getKey() + "...");
	        	idempotencyStoreIterator.remove(); // Safely removes the entry
	        }
	    }
	}

    
	@Override
	public void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		addHeadersToResponse(response);

        response.setContentType("application/json; charset=utf-8");

	    String output = "{\"result\": \"success\", \"message\": \"preflight successful\"} ";
	    PrintWriter out = response.getWriter();
	    out.println(output);
	    return;
	}
	
	/*
	public static void _logout(HttpSession session) {
	    // first close all running AsyncContext running on this session and for this user for push notification
	    CApiCommon.closeInvalidatedAsyncContext(session.getId(), Integer.parseInt(""+session.getAttribute("user_id")));
	    
	    CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "==> invalidating session...");
	    session.invalidate();
	    synchronized(sessionVector) {
	    	sessionVector.remove(session);
	    }
	}
	*/
    
    
	@Override
	public void destroy() {
		CTraceUtils.trace(LOG_PRIORITY_SEVERE, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "DESTROY");
		
		// Stop threads running
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "stopping threadFreeConnections...");
        threadFreeConnections.stop();
        threadFreeConnections.waitThreadToGentlyFinish(); // maximum time to wait here = time to free the connections + 5 seconds = approx 5 seconds
        
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "stopping dailyProcess...");
        dailyProcess.stop();
        dailyProcess.waitThreadToGentlyFinish(); // maximum time to wait here = IF (this is called while the daily process is running) time to execute the daily process + 8.23 seconds ELSE 6 seconds

        CUtils.sleep(1000);
        
        // invalidate all opened sessions
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "invalidating " + sessionVector.size() + " sessions...");
        synchronized(sessionVector) {
        	for (HttpSession session : sessionVector) {
        		if (session != null) {
            		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- invalidating " + session.getId() + "...");
        			session.invalidate();
        		} else {
            		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "- null session, no need to invalidate");
        		}
        	}
        }

        /*
		CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "deleting all SQL connections...");
        CStudioGlobals.bugtrackingConnectionFactory.getPool().releaseAllFreeConnections();
        CStudioGlobals.applicationConnectionFactory.getPool().releaseAllFreeConnections();
        */
        
		// deregister all loaded drivers
		Enumeration<Driver> registeredDrivers = DriverManager.getDrivers(); // i.e. SQL JDBC drivers
		while (registeredDrivers.hasMoreElements()) {
			Driver thisDriver = registeredDrivers.nextElement();
			CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "deregistering driver " + thisDriver);
			try {
				DriverManager.deregisterDriver(thisDriver);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

        // Clear the push context map
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "clearing the push context map...");
        pushContextMap = new LinkedHashMap<Integer, List<AsyncContext>>();

        // Clear the session vector
        CTraceUtils.trace(LOG_PRIORITY_INFO, CStudioGlobals.traceLevel, LOG_API_SERVER, LOG_API_SERVER_LABEL, "clearing the session vector...");
        sessionVector  = new Vector<HttpSession>();
        
		super.destroy();
	}
}
