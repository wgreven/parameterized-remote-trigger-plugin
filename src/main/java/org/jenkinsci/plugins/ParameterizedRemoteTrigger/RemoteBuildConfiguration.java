package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.CopyOnWriteList;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 
 * @author Maurice W.
 * 
 */
public class RemoteBuildConfiguration extends Builder {

    private final String          token;
    private final String          remoteJenkinsName;
    private final String          job;

    private final boolean         shouldNotFailBuild;
    private final int             pollInterval;
    private final int             connectionRetryLimit = 5;
    private final boolean         preventRemoteBuildQueue;
    private final boolean         blockBuildUntilComplete;

    // "parameters" is the raw string entered by the user
    private final String          parameters;
    // "parameterList" is the cleaned-up version of "parameters" (stripped out comments, character encoding, etc)

    private final List<String>    parameterList;

    private final boolean         overrideAuth;
    private CopyOnWriteList<Auth> auth                = new CopyOnWriteList<Auth>();

    private final boolean         loadParamsFromFile;
    private String                parameterFile       = "";

    @DataBoundConstructor
    public RemoteBuildConfiguration(String remoteJenkinsName, boolean shouldNotFailBuild, String job, String token,
            String parameters, JSONObject overrideAuth, JSONObject loadParamsFromFile, boolean preventRemoteBuildQueue,
            boolean blockBuildUntilComplete, int pollInterval) throws MalformedURLException {

        this.token = token.trim();
        this.remoteJenkinsName = remoteJenkinsName;
        this.parameters = parameters;
        this.job = job.trim();
        this.shouldNotFailBuild = shouldNotFailBuild;
        this.preventRemoteBuildQueue = preventRemoteBuildQueue;
        this.blockBuildUntilComplete = blockBuildUntilComplete;
        this.pollInterval = pollInterval;

        if (overrideAuth != null && overrideAuth.has("auth")) {
            this.overrideAuth = true;
            this.auth.replaceBy(new Auth(overrideAuth.getJSONObject("auth")));
        } else {
            this.overrideAuth = false;
            this.auth.replaceBy(new Auth(new JSONObject()));
        }

        if (loadParamsFromFile != null && loadParamsFromFile.has("parameterFile")) {
            this.loadParamsFromFile = true;
            this.parameterFile = loadParamsFromFile.getString("parameterFile");
            //manually add a leading-slash if we don't have one
            if( this.parameterFile.charAt(0) != '/' ){
                this.parameterFile = "/" + this.parameterFile;
            }
        } else {
            this.loadParamsFromFile = false;
        }

        // TODO: clean this up a bit
        // split the parameter-string into an array based on the new-line character
        String[] params = parameters.split("\n");

        // convert the String array into a List of Strings, and remove any empty entries
        this.parameterList = new ArrayList<String>(Arrays.asList(params));

    }

    public RemoteBuildConfiguration(String remoteJenkinsName, boolean shouldNotFailBuild,
            boolean preventRemoteBuildQueue, boolean blockBuildUntilComplete, int pollInterval, String job,
            String token, String parameters) throws MalformedURLException {

        this.token = token.trim();
        this.remoteJenkinsName = remoteJenkinsName;
        this.parameters = parameters;
        this.job = job.trim();
        this.shouldNotFailBuild = shouldNotFailBuild;
        this.preventRemoteBuildQueue = preventRemoteBuildQueue;
        this.blockBuildUntilComplete = blockBuildUntilComplete;
        this.pollInterval = pollInterval;
        this.overrideAuth = false;
        this.auth.replaceBy(new Auth(null));

        this.loadParamsFromFile = false;

        // split the parameter-string into an array based on the new-line character
        String[] params = parameters.split("\n");

        // convert the String array into a List of Strings, and remove any empty entries
        this.parameterList = new ArrayList<String>(Arrays.asList(params));

    }

    /**
     * Reads a file from the jobs workspace, and loads the list of parameters from with in it. It will also call
     * ```getCleanedParameters``` before returning.
     * 
     * @param build
     * @return List<String> of build parameters
     */
    private List<String> loadExternalParameterFile(AbstractBuild<?, ?> build) {

        FilePath workspace = build.getWorkspace();
        BufferedReader br = null;
        List<String> ParameterList = new ArrayList<String>();
        try {

            String filePath = workspace + this.getParameterFile();
            String sCurrentLine;
            String fileContent = "";

            br = new BufferedReader(new FileReader(filePath));

            while ((sCurrentLine = br.readLine()) != null) {
                // fileContent += sCurrentLine;
                ParameterList.add(sCurrentLine);
            }

            // ParameterList = new ArrayList<String>(Arrays.asList(fileContent));

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        // FilePath.
        return getCleanedParameters(ParameterList);
    }

    /**
     * Strip out any empty strings from the parameterList
     */
    private void removeEmptyElements(Collection<String> collection) {
        collection.removeAll(Arrays.asList(null, ""));
        collection.removeAll(Arrays.asList(null, " "));
    }

    /**
     * Convenience method
     * 
     * @return List<String> of build parameters
     */
    private List<String> getCleanedParameters() {

        return getCleanedParameters(this.getParameterList());
    }

    /**
     * Same as "getParameterList", but removes comments and empty strings Notice that no type of character encoding is
     * happening at this step. All encoding happens in the "buildUrlQueryString" method.
     * 
     * @param List
     *            <String> parameters
     * @return List<String> of build parameters
     */
    private List<String> getCleanedParameters(List<String> parameters) {
        List<String> params = new ArrayList<String>(parameters);
        removeEmptyElements(params);
        removeCommentsFromParameters(params);
        return params;
    }

    /**
     * Similar to "replaceToken", but acts on a list in place of just a single string
     * 
     * @param build

     * @param listener
     * @param params
     *            List<String> of params to be tokenized/replaced
     * @return List<String> of resolved variables/tokens
     */
    private List<String> replaceTokens(AbstractBuild<?, ?> build, BuildListener listener, List<String> params) {
        List<String> tokenizedParams = new ArrayList<String>();

        for (int i = 0; i < params.size(); i++) {
            tokenizedParams.add(replaceToken(build, listener, params.get(i)));
            // params.set(i, replaceToken(build, listener, params.get(i)));
        }

        return tokenizedParams;
    }

    /**
     * Resolves any environment variables in the string
     * 
     * @param build
     * @param listener
     * @param input
     *            String to be tokenized/replaced
     * @return String with resolved Environment variables
     */
    private String replaceToken(AbstractBuild<?, ?> build, BuildListener listener, String input) {
        try {
            return TokenMacro.expandAll(build, listener, input);
        } catch (Exception e) {
            listener.getLogger().println(
                    String.format("Failed to resolve parameters in string %s due to following error:\n%s", input,
                            e.getMessage()));
        }
        return input;
    }

    /**
     * Strip out any comments (lines that start with a #) from the collection that is passed in.
     */
    private void removeCommentsFromParameters(Collection<String> collection) {
        List<String> itemsToRemove = new ArrayList<String>();

        for (String parameter : collection) {
            if (parameter.indexOf("#") == 0) {
                itemsToRemove.add(parameter);
            }
        }
        collection.removeAll(itemsToRemove);
    }

    /**
     * Lookup up a Remote Jenkins Server based on display name
     * 
     * @param displayName
     *            Name of the configuration you are looking for
     * @return A RemoteSitez object
     */
    public RemoteJenkinsServer findRemoteHost(String displayName) {
        RemoteJenkinsServer match = null;

        for (RemoteJenkinsServer host : this.getDescriptor().remoteSites) {
            // if we find a match, then stop looping
            if (displayName.equals(host.getDisplayName())) {
                match = host;
                break;
            }
        }

        return match;
    }

    /**
     * Convenience function to mark the build as failed. It's intended to only be called from this.perform();
     * 
     * @param e
     *            Exception that caused the build to fail
     * @param listener
     *            Build Listener
     * @throws IOException
     */
    private void failBuild(Exception e, BuildListener listener) throws IOException {
        System.out.print(e.getStackTrace());
        if (this.getShouldNotFailBuild()) {
            listener.error("Remote build failed for the following reason, but the build will continue:");
            listener.error(e.getMessage());
        } else {
            listener.error("Remote build failed for the following reason:");
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException,
            IOException, IllegalArgumentException {
        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());
        if (remoteServer == null) {
            this.failBuild(new Exception("No remote host is defined for this job."), listener);
            return true;
        }

        List<String> cleanedParams = null;
        if (this.getLoadParamsFromFile()) {
            cleanedParams = loadExternalParameterFile(build);
        } else {
            // tokenize all variables and encode all variables, then build the fully-qualified trigger URL
            cleanedParams = getCleanedParameters();
            cleanedParams = replaceTokens(build, listener, cleanedParams);
        }

        String jobName = replaceToken(build, listener, this.getJob());

        String securityToken = replaceToken(build, listener, this.getToken());

        // if there is a username + apiToken defined for this remote host, then use it
        String usernameTokenConcat;

        if (this.getOverrideAuth()) {
            listener.getLogger().println(
                    "Using job-level defined credentails in place of those from remote Jenkins config ["
                            + remoteServer.getDisplayName() + "]");
            usernameTokenConcat = this.getAuth()[0].getUsername() + ":" + this.getAuth()[0].getPassword();
        } else {
            usernameTokenConcat = remoteServer.getAuth()[0].getUsername() + ":"
                    + remoteServer.getAuth()[0].getPassword();
        }

        if (!usernameTokenConcat.equals(":")) {
            // token-macro replacment
            try {
                usernameTokenConcat = TokenMacro.expandAll(build, listener, usernameTokenConcat);
            } catch (MacroEvaluationException e) {
                this.failBuild(e, listener);
            } catch (InterruptedException e) {
                this.failBuild(e, listener);
            }
        }

        try {
            RemoteJob remoteJob = new RemoteJob(remoteServer, usernameTokenConcat, jobName, securityToken,
                    this.pollInterval, this.preventRemoteBuildQueue);
            int remoteBuildNumber = remoteJob.build(listener, cleanedParams);
            BuildInfoExporterAction.addBuildInfoExporterAction(build, this.job, remoteBuildNumber, Result.NOT_BUILT);
            if (this.blockBuildUntilComplete) {
                Result remoteBuidlResult = remoteJob.waitUntilComplete(listener, remoteBuildNumber);
                BuildInfoExporterAction.addBuildInfoExporterAction(build, this.job, remoteBuildNumber, remoteBuidlResult);
                // If build did not finish with 'success' then fail build step.
                if (!Result.SUCCESS.equals(remoteBuidlResult)) {
                    throw new RemoteBuildException("The remote job did not succeed.");
                }
            } else {
                listener.getLogger().println("Not blocking local job until remote job completes - fire and forget.");
            }
        } catch (RemoteBuildException e) {
            this.failBuild(e, listener);
        }
        return true;
    }

    // Getters
    public String getRemoteJenkinsName() {
        return this.remoteJenkinsName;
    }

    public String getJob() {
        return this.job;
    }

    public boolean getShouldNotFailBuild() {
        return this.shouldNotFailBuild;
    }

    public boolean getPreventRemoteBuildQueue() {
        return this.preventRemoteBuildQueue;
    }

    public boolean getBlockBuildUntilComplete() {
        return this.blockBuildUntilComplete;
    }

    public int getPollInterval() {
        return this.pollInterval;
    }

    /**
     * @return the connectionRetryLimit
     */
    public int getConnectionRetryLimit() {
        return connectionRetryLimit;
    }

    public String getToken() {
        return this.token;
    }

    public boolean getLoadParamsFromFile() {
        return this.loadParamsFromFile;
    }
    
    public String getParameterFile() {
        return this.parameterFile;
    }

    public boolean getOverrideAuth() {
        return this.overrideAuth;
    }

    public Auth[] getAuth() {
        return auth.toArray(new Auth[this.auth.size()]);

    }

    public String getParameters() {
        return this.parameters;
    }

    private List<String> getParameterList() {
        return this.parameterList;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    // This indicates to Jenkins that this is an implementation of an extension
    // point.
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information, simply store it in a field and call save().
         * 
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private CopyOnWriteList<RemoteJenkinsServer> remoteSites = new CopyOnWriteList<RemoteJenkinsServer>();

        /**
         * In order to load the persisted global configuration, you have to call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         * 
         * @param value
         *            This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        /*
         * public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException { if
         * (value.length() == 0) return FormValidation.error("Please set a name"); if (value.length() < 4) return
         * FormValidation.warning("Isn't the name too short?"); return FormValidation.ok(); }
         */

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project
            // types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Trigger a remote parameterized job";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            remoteSites.replaceBy(req.bindJSONToList(RemoteJenkinsServer.class, formData.get("remoteSites")));
            save();

            return super.configure(req, formData);
        }

        public ListBoxModel doFillRemoteJenkinsNameItems() {
            ListBoxModel model = new ListBoxModel();

            for (RemoteJenkinsServer site : getRemoteSites()) {
                model.add(site.getDisplayName());
            }

            return model;
        }

        public RemoteJenkinsServer[] getRemoteSites() {

            return remoteSites.toArray(new RemoteJenkinsServer[this.remoteSites.size()]);
        }
    }
}
