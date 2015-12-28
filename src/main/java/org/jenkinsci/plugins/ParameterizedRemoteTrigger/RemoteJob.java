package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import net.sf.json.util.JSONUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 
 * @author Maurice W.
 * 
 */
public class RemoteJob {

    private final RemoteJenkinsServer remoteServer;
    private final String          usernameTokenConcat;
    private final String          job;
    private final String          token;

    private final int             pollInterval;
    private final int             connectionRetryLimit = 5;
    private final boolean         preventRemoteBuildQueue;

    private static String         parameterizedBuildUrl = "/buildWithParameters";
    private static String         normalBuildUrl      = "/build";
    private static String         buildTokenRootUrl   = "/buildByToken";

    public RemoteJob(RemoteJenkinsServer remoteServer, final String usernameTokenConcat, String job, String token,
                     int pollInterval, boolean preventRemoteBuildQueue) {
        this.remoteServer = remoteServer;
        this.usernameTokenConcat = usernameTokenConcat;
        this.job = job;
        this.token = token;
        this.pollInterval = pollInterval;
        this.preventRemoteBuildQueue = preventRemoteBuildQueue;
    }

    /**
     * Return the Collection<String> in an encoded query-string
     * 
     * @return query-parameter-formated URL-encoded string
     */
    private QueryStringBuilder buildUrlQueryString(QueryStringBuilder queryStringBuilder, Collection<String> parameters) {
        for (String parameter : parameters) {
            String[] splitParameters = parameter.split("=");
            queryStringBuilder.addField(splitParameters[0], splitParameters[1]);
        }
        return queryStringBuilder;
    }

    /**
     * Build the proper URL to trigger the remote build
     * 
     * All passed in string have already had their tokens replaced with real values. All 'params' also have the proper
     * character encoding
     * 
     * @param params
     *            Parameters for the remote job
     * @return fully formed, fully qualified remote trigger URL
     */
    private String buildTriggerUrl(Collection<String> params, boolean isRemoteJobParameterized) {
        String triggerUrlString = this.remoteServer.getAddress().toString();

        String buildTypeUrl;
        if (isRemoteJobParameterized || !params.isEmpty()) {
            buildTypeUrl = RemoteJob.parameterizedBuildUrl;
        } else {
            buildTypeUrl = RemoteJob.normalBuildUrl;
        }

        QueryStringBuilder queryStringBuilder = new QueryStringBuilder();

        // start building the proper URL based on known capabiltiies of the remote server
        if (this.remoteServer.getHasBuildTokenRootSupport()) {
            triggerUrlString += buildTokenRootUrl;
            triggerUrlString += buildTypeUrl;

            queryStringBuilder.addField("job", this.job);
        } else {
            triggerUrlString += "/job/";
            triggerUrlString += encodeValue(this.job);
            triggerUrlString += buildTypeUrl;
        }

        // don't try to include a security token in the URL if none is provided
        if (!this.token.equals("")) {
            queryStringBuilder.addField("token", this.token);
        }

        // turn our Collection into a query string
        buildUrlQueryString(queryStringBuilder, params);

        // by adding "delay=0", this will (theoretically) force this job to the top of the remote queue
        queryStringBuilder.addField("delay", "0");

        triggerUrlString += "?" + queryStringBuilder.build();

        return triggerUrlString;
    }

    /**
     * Build the proper URL for GET calls
     *
     * @return fully formed, fully qualified remote trigger URL
     */
    private String buildGetUrl() {
        String urlString = this.remoteServer.getAddress().toString();

        urlString += "/job/";
        urlString += encodeValue(this.job);

        return urlString;
    }

    public int build(TaskListener listener, List<String> parameters) throws RemoteBuildException {
        String remoteServerURL = this.remoteServer.getAddress().toString();

        boolean isRemoteParameterized = isRemoteJobParameterized(listener);
        String triggerUrlString = this.buildTriggerUrl(parameters, isRemoteParameterized);

        // Trigger remote job
        // print out some debugging information to the console

        //listener.getLogger().println("URL: " + triggerUrlString);
        listener.getLogger().println("Triggering this remote job: " + this.job);

        // get the ID of the Next Job to build.
        if (this.preventRemoteBuildQueue) {
            listener.getLogger().println("Checking that the remote job " + this.job + " is not currently building.");
            String preCheckUrlString = this.buildGetUrl();
            preCheckUrlString += "/lastBuild";
            preCheckUrlString += "/api/json/";
            JSONObject preCheckResponse = sendHTTPCall(preCheckUrlString, "GET", listener);

            if (preCheckResponse != null) {
                // check the latest build on the remote server to see if it's running - if so wait until it has stopped.
                // if building is true then the build is running
                // if result is null the build hasn't finished - but might not have started running.
                while (preCheckResponse.getBoolean("building") == true || preCheckResponse.getString("result") == null) {
                    listener.getLogger().println("Remote build is currently running - waiting for it to finish.");
                    preCheckResponse = sendHTTPCall(preCheckUrlString, "POST", listener);
                    listener.getLogger().println("Waiting for " + this.pollInterval + " seconds until next retry.");

                    // Sleep for 'pollInterval' seconds.
                    // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                    try {
                        Thread.sleep(this.pollInterval * 1000);
                    } catch (InterruptedException e) {
                        throw new RemoteBuildException(e);
                    }
                }
                listener.getLogger().println("Remote job remote job " + this.job + " is not currenlty building.");
            } else {
                throw new RemoteBuildException("Got a blank response from Remote Jenkins Server, cannot continue.");
            }

        } else {
            listener.getLogger().println("Not checking if the remote job " + this.job + " is building.");
        }

        String queryUrlString = this.buildGetUrl();
        queryUrlString += "/api/json/";

        //listener.getLogger().println("Getting ID of next job to build. URL: " + queryUrlString);
        JSONObject queryResponseObject = sendHTTPCall(queryUrlString, "GET", listener);
        if (queryResponseObject == null) {
            //This should not happen as this page should return a JSON object
            throw new RemoteBuildException("Got a blank response from Remote Jenkins Server [" + remoteServerURL + "], cannot continue.");
        }

        int nextBuildNumber = queryResponseObject.getInt("nextBuildNumber");

        listener.getLogger().println("Triggering remote job now.");
        sendHTTPCall(triggerUrlString, "POST", listener);
        // Validate the build number via parameters
        foundIt:
        for (int tries = 3; tries > 0; tries--) {
            for (int buildNumber : new SearchPattern(nextBuildNumber, 2)) {
                listener.getLogger().println("Checking parameters of #" + buildNumber);
                String validateUrlString = this.buildGetUrl() + "/" + buildNumber + "/api/json/";
                JSONObject validateResponse = sendHTTPCall(validateUrlString, "GET", listener);
                if (validateResponse == null) {
                    listener.getLogger().println("Query failed.");
                    continue;
                }
                JSONArray actions = validateResponse.getJSONArray("actions");
                for (int i = 0; i < actions.size(); i++) {
                    JSONObject action = actions.getJSONObject(i);
                    if (!action.has("parameters")) continue;
                    JSONArray actionParameters = action.getJSONArray("parameters");
                    // Check if the parameters match
                    if (compareParameters(listener, actionParameters, parameters)) {
                        // We now have a very high degree of confidence that this is the correct build.
                        // It is still possible that this is a false positive if there are no parameters,
                        // or multiple jobs use the same parameters.
                        nextBuildNumber = buildNumber;
                        break foundIt;
                    }
                    // This is the wrong build
                    break;
                }

                // Sleep for 'pollInterval' seconds.
                // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                try {
                    Thread.sleep(this.pollInterval * 1000);
                } catch (InterruptedException e) {
                    throw new RemoteBuildException(e);
                }
            }
        }
        listener.getLogger().println("This job is build #[" + Integer.toString(nextBuildNumber) + "] on the remote server.");

        // This is only for Debug
        // This output whether there is another job running on the remotblockBuildUntilCompletee host that this job had conflicted with.
        // The first condition is what is expected, The second is what would happen if two jobs launched jobs at the
        // same time (and two remote builds were triggered).
        // The third is what would happen if this job was triggers and the remote queue was already full (as the 'next
        // build bumber' would still be the same after this job has triggered the remote job)
        // int newNextBuildNumber = responseObject.getInt( "nextBuildNumber" ); // This should be nextBuildNumber + 1 OR
        // there has been another job scheduled.
        // if (newNextBuildNumber == (nextBuildNumber + 1)) {
        // listener.getLogger().println("DEBUG: No other jobs triggered" );
        // } else if( newNextBuildNumber > (nextBuildNumber + 1) ) {
        // listener.getLogger().println("DEBUG: WARNING Other jobs triggered," + newNextBuildNumber + ", " +
        // nextBuildNumber );
        // } else {
        // listener.getLogger().println("DEBUG: WARNING Did not get the correct build number for the triggered job, previous nextBuildNumber:"
        // + newNextBuildNumber + ", newNextBuildNumber" + nextBuildNumber );
        // }

        return nextBuildNumber;
    }

    public Result waitUntilComplete(TaskListener listener, int buildNumber) throws RemoteBuildException {
        String remoteServerURL = this.remoteServer.getAddress().toString();

        //Have to form the string ourselves, as we might not get a response from non-parameterized builds
        String jobURL = remoteServerURL + "/job/" + encodeValue(this.job) + "/";

        listener.getLogger().println("Blocking local job until remote job completes");
        // Form the URL for the triggered job
        String jobLocation = jobURL + buildNumber + "/api/json";

        String buildStatusStr = getBuildStatus(jobLocation, listener);

        while (buildStatusStr.equals("not started")) {
            listener.getLogger().println("Waiting for remote build to start.");
            listener.getLogger().println("Waiting for " + this.pollInterval + " seconds until next poll.");
            buildStatusStr = getBuildStatus(jobLocation, listener);
            // Sleep for 'pollInterval' seconds.
            // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
            try {
                // Could do with a better way of sleeping...
                Thread.sleep(this.pollInterval * 1000);
            } catch (InterruptedException e) {
                throw new RemoteBuildException(e);
            }
        }

        listener.getLogger().println("Remote build started!");
        while (buildStatusStr.equals("running")) {
            listener.getLogger().println("Waiting for remote build to finish.");
            listener.getLogger().println("Waiting for " + this.pollInterval + " seconds until next poll.");
            buildStatusStr = getBuildStatus(jobLocation, listener);
            // Sleep for 'pollInterval' seconds.
            // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
            try {
                // Could do with a better way of sleeping...
                Thread.sleep(this.pollInterval * 1000);
            } catch (InterruptedException e) {
                throw new RemoteBuildException(e);
            }
        }
        listener.getLogger().println("Remote build finished with status " + buildStatusStr + ".");

        return Result.fromString(buildStatusStr);
    }

    private static String findParameter(String parameter, List<String> parameters) {
        for (String search : parameters) {
            if (search.startsWith(parameter + "=")) {
                return search.substring(parameter.length() + 1);
            }
        }
        return null;
    }

    private static boolean compareParameters(TaskListener listener, JSONArray parameters, List<String> expectedParams) {
        for (int j = 0; j < parameters.size(); j++) {
            JSONObject parameter = parameters.getJSONObject(j);
            String name = parameter.getString("name");
            String value = parameter.getString("value");
            String expected = findParameter(name, expectedParams);

            if (expected == null) {
                // If we didn't specify all of the parameters, this will happen, so we can not infer that this it he wrong build
                listener.getLogger().println("Unable to find expected value for " + name);
                continue;
            }

            // If we got the expected value, skip to the next parameter
            if (expected.equals(value)) continue;

            // We didn't get the expected value
            listener.getLogger().println("Param " + name + " doesn't match!");
            return false;
        }
        // All found parameters matched. This if there are no uniquely identifying parameters, this could still be a false positive.
        return true;
    }

    public String getBuildStatus(String buildUrlString, TaskListener listener) throws RemoteBuildException {
        String buildStatus = "UNKNOWN";

        if (this.remoteServer == null) {
            throw new RemoteBuildException("No remote host is defined for this job.");
        }

        JSONObject responseObject = sendHTTPCall(buildUrlString, "GET", listener);

        // get the next build from the location

        if (responseObject == null || responseObject.getString("result") == null && responseObject.getBoolean("building") == false) {
            // build not started
            buildStatus = "not started";
        } else if (responseObject.getBoolean("building")) {
            // build running
            buildStatus = "running";
        } else if (responseObject.getString("result") != null) {
            // build finished
            buildStatus = responseObject.getString("result");
        } else {
            // Add additional else to check for unhandled conditions
            listener.getLogger().println("WARNING: Unhandled condition!");
        }

        return buildStatus;
    }

    /**
     * Orchestrates all calls to the remote server.
     * Also takes care of any credentials or failed-connection retries.
     * 
     * @param urlString     the URL that needs to be called
     * @param requestType   the type of request (GET, POST, etc)
     * @param listener      build listener
     * @return              a valid JSON object, or null
     * @throws RemoteBuildException
     */
    public JSONObject sendHTTPCall(String urlString, String requestType, TaskListener listener)
            throws RemoteBuildException {
        
            return sendHTTPCall( urlString, requestType, listener, 1 );
    }

    /**
     * Same as sendHTTPCall, but keeps track of the number of failed connection attempts (aka: the number of times this
     * method has been called).
     * In the case of a failed connection, the method calls it self recursively and increments  numberOfAttempts
     * 
     * @see sendHTTPCall
     * @param numberOfAttempts  number of time that the connection has been attempted
     * @return
     * @throws RemoteBuildException
     */
    public JSONObject sendHTTPCall(String urlString, String requestType, TaskListener listener, int numberOfAttempts)
            throws RemoteBuildException {
        int retryLimit = this.getConnectionRetryLimit();
        
        HttpURLConnection connection = null;

        JSONObject responseObject = null;

        try {
            URL buildUrl = new URL(urlString);
            connection = (HttpURLConnection) buildUrl.openConnection();

            if (!this.usernameTokenConcat.equals(":")) {
                byte[] encodedAuthKey = Base64.encodeBase64(this.usernameTokenConcat.getBytes());
                connection.setRequestProperty("Authorization", "Basic " + new String(encodedAuthKey));
            }

            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestMethod(requestType);
            // wait up to 5 seconds for the connection to be open
            connection.setConnectTimeout(5000);
            connection.connect();
            
            InputStream is = connection.getInputStream();
            
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            // String response = "";
            StringBuilder response = new StringBuilder();
        
            while ((line = rd.readLine()) != null) {
                response.append(line);
            }
            rd.close();
            
            // JSONSerializer serializer = new JSONSerializer();
            // need to parse the data we get back into struct
            //listener.getLogger().println("Called URL: '" + urlString +  "', got response: '" + response.toString() + "'");

            //Solving issue reported in this comment: https://github.com/jenkinsci/parameterized-remote-trigger-plugin/pull/3#issuecomment-39369194
            //Seems like in Jenkins version 1.547, when using "/build" (job API for non-parameterized jobs), it returns a string indicating the status.
            //But in newer versions of Jenkins, it just returns an empty response.
            //So we need to compensate and check for both.
            if ( JSONUtils.mayBeJSON(response.toString()) == false) {
                listener.getLogger().println("Remote Jenkins server returned empty response or invalid JSON - but we can still proceed with the remote build.");
                return null;
            } else {
                responseObject = (JSONObject) JSONSerializer.toJSON(response.toString());
            }

        } catch (IOException e) {
            
            //If we have connectionRetryLimit set to > 0 then retry that many times.
            if( numberOfAttempts <= retryLimit) {
                listener.getLogger().println("Connection to remote server failed, waiting for to retry - " + this.pollInterval + " seconds until next attempt.");
                
                // Sleep for 'pollInterval' seconds.
                // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                try {
                    // Could do with a better way of sleeping...
                    Thread.sleep(this.pollInterval * 1000);
                } catch (InterruptedException ex) {
                    throw new RemoteBuildException(ex);
                }
 
                listener.getLogger().println("Retry attempt #" + numberOfAttempts + " out of " + retryLimit );
                numberOfAttempts++;
                responseObject = sendHTTPCall(urlString, requestType, listener, numberOfAttempts);
            }else if(numberOfAttempts > retryLimit){
                //reached the maximum number of retries, time to fail
                throw new RemoteBuildException("Max number of connection retries have been exeeded.");
            }else{
                //something failed with the connection and we retried the max amount of times... so throw an exception to mark the build as failed.
                throw new RemoteBuildException(e);
            }
            
        } finally {
            // always make sure we close the connection
            if (connection != null) {
                connection.disconnect();
            }
        }
        return responseObject;
    }

    /**
     * Helper function for character encoding
     * 
     * @param dirtyValue
     * @return encoded value
     */
    private static String encodeValue(String dirtyValue) {
        String cleanValue = "";

        try {
            cleanValue = URLEncoder.encode(dirtyValue, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return cleanValue;
    }

    /**
     * @return the connectionRetryLimit
     */
    public int getConnectionRetryLimit() {
        return connectionRetryLimit;
    }

    /**
     * Pokes the remote server to see if it has default parameters defined or not.
     *
     * @param listener listner object
     * @return true if the remote job has default parameters set, otherwise false
     */
    private boolean isRemoteJobParameterized(TaskListener listener) throws RemoteBuildException {
        //build the proper URL to inspect the remote job
        String remoteServerUrl = this.remoteServer.getAddress().toString();
        remoteServerUrl += "/job/" + encodeValue(this.job);
        remoteServerUrl += "/api/json";
        
        JSONObject response = sendHTTPCall(remoteServerUrl, "GET", listener);

        return response.getJSONArray("actions").size() >= 1;
    }

    public class QueryStringBuilder {
        private final List<String> fields = new ArrayList<String>();

        public QueryStringBuilder addField(String name, String value) {
            fields.add(encodeValue(name) + "=" + encodeValue(value));
            return this;
        }

        public String build() {
            return StringUtils.join(fields, "&");
        }
    }
}
