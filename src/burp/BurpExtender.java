package burp;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringEscapeUtils;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import com.google.gson.GsonBuilder;

import javax.swing.*;

public class BurpExtender implements IBurpExtender {
    private static final String name = "Backslash Powered Scanner";
    private static final String version = "0.91";

    @Override
    public void registerExtenderCallbacks(final IBurpExtenderCallbacks callbacks) {

        new Utilities(callbacks);
        callbacks.setExtensionName(name);


        try {
            StringUtils.isNumeric("1");
        } catch (java.lang.NoClassDefFoundError e) {
            Utilities.out("Failed to import the Apache Commons Lang library. You can get it from http://commons.apache.org/proper/commons-lang/");
            throw new NoClassDefFoundError();
        }

        try {
            callbacks.getHelpers().analyzeResponseVariations();
        } catch (java.lang.NoSuchMethodError e) {
            Utilities.out("This extension requires Burp Suite Pro 1.7.10 or later");
            throw new NoSuchMethodError();
        }

        FastScan scan = new FastScan(callbacks);
        callbacks.registerScannerCheck(scan);
        callbacks.registerExtensionStateListener(scan);
        callbacks.registerContextMenuFactory(new OfferParamGuess(callbacks));

        Utilities.out("Loaded " + name + " v" + version);
        Utilities.out("Debug mode: " + Utilities.DEBUG);
        Utilities.out("Thorough mode: " + Utilities.THOROUGH_MODE);
        Utilities.out("Input transformation detection: " + Utilities.TRANSFORMATION_SCAN);
        Utilities.out("Suspicious input handling detection: " + Utilities.DIFFING_SCAN);
        Utilities.out("    TRY_SYNTAX_ATTACKS "+Utilities.TRY_SYNTAX_ATTACKS);
        Utilities.out("    TRY_VALUE_PRESERVING_ATTACKS "+Utilities.TRY_VALUE_PRESERVING_ATTACKS);
        Utilities.out("    TRY_EXPERIMENTAL_CONCAT_ATTACKS "+Utilities.TRY_EXPERIMENTAL_CONCAT_ATTACKS);
        Utilities.out("    TRY_HPP "+Utilities.TRY_HPP);
        Utilities.out("    TRY_HPP_FOLLOWUP "+Utilities.TRY_HPP_FOLLOWUP);
        Utilities.out("    TRY_MAGIC_VALUE_ATTACKS "+Utilities.TRY_MAGIC_VALUE_ATTACKS);

    }


}

class FastScan implements IScannerCheck, IExtensionStateListener {
    private TransformationScan transformationScan;
    private DiffingScan diffingScan;
    private IExtensionHelpers helpers;
    private IBurpExtenderCallbacks callbacks;

    FastScan(final IBurpExtenderCallbacks callbacks) {
        transformationScan = new TransformationScan(callbacks);
        diffingScan = new DiffingScan();
        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();
    }

    public void extensionUnloaded() {
        Utilities.out("Unloading extension...");
        Utilities.unloaded.set(true);
    }

    private IParameter getParameterFromInsertionPoint(IScannerInsertionPoint insertionPoint, byte[] request) {
        IParameter baseParam = null;
        int basePayloadStart = insertionPoint.getPayloadOffsets("x".getBytes())[0];
        List<IParameter> params = helpers.analyzeRequest(request).getParameters();
        for (IParameter param : params) {
            if (param.getValueStart() == basePayloadStart && insertionPoint.getBaseValue().equals(param.getValue())) {
                baseParam = param;
                break;
            }
        }
        return baseParam;
    }

    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {

        ArrayList<IScanIssue> issues = new ArrayList<>();
        if(!(Utilities.TRANSFORMATION_SCAN || Utilities.DIFFING_SCAN)) {
            Utilities.out("Aborting scan - all scanner checks disabled");
            return issues;
        }

        // make a custom insertion point to avoid burp excessively URL-encoding payloads
        IParameter baseParam = getParameterFromInsertionPoint(insertionPoint, baseRequestResponse.getRequest());
        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL)) {
            insertionPoint = new ParamInsertionPoint(baseRequestResponse.getRequest(), baseParam.getName(), baseParam.getValue(), baseParam.getType());
        }

        if (Utilities.TRANSFORMATION_SCAN) {
            issues.add(transformationScan.findTransformationIssues(baseRequestResponse, insertionPoint));
        }

        if (Utilities.DIFFING_SCAN) {
            issues.add(diffingScan.findReflectionIssues(baseRequestResponse, insertionPoint));
        }

        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL) && Utilities.getExtension(baseRequestResponse.getRequest()).equals(".php")) {
            String param_name = baseParam.getName() + "[]";
            byte[] newReq = helpers.removeParameter(baseRequestResponse.getRequest(), baseParam);
            IParameter newParam = helpers.buildParameter(param_name, baseParam.getValue(), baseParam.getType());
            newReq = helpers.addParameter(newReq, helpers.buildParameter(param_name, "", baseParam.getType()));
            newReq = helpers.addParameter(newReq, newParam);

            IScannerInsertionPoint arrayInsertionPoint = new ParamInsertionPoint(newReq, param_name, newParam.getValue(), newParam.getType());
            IHttpRequestResponse newBase = callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), arrayInsertionPoint.buildRequest(newParam.getValue().getBytes()));

            if (Utilities.TRANSFORMATION_SCAN) {
                issues.add(transformationScan.findTransformationIssues(newBase, arrayInsertionPoint));
            }

            if (Utilities.DIFFING_SCAN) {
                issues.add(diffingScan.findReflectionIssues(newBase, arrayInsertionPoint));
            }
        }

        return issues
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        return new ArrayList<>();

    }

    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        if (existingIssue.getIssueName().equals(newIssue.getIssueName()) && existingIssue.getIssueDetail().equals(newIssue.getIssueDetail()))
            return -1;
        else return 0;
    }
}


class Fuzzable extends CustomScanIssue {
    private final static String NAME = "Interesting input handling: ";
    private final static String DETAIL = "The application reacts to inputs in a way that suggests it might be vulnerable to some kind of server-side code injection. The probes are listed below in chronological order, with evidence. Response attributes that only stay consistent in one probe-set are italicised, with the variable attribute starred.";
    private final static String REMEDIATION = "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. Try to determine the root cause of the observed behaviour." +
            "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results. ";

    Fuzzable(IHttpRequestResponse[] requests, URL url, String title, String detail, boolean reliable, String severity) {
        super(requests[0].getHttpService(), url, requests, NAME + title, DETAIL + detail, severity, calculateConfidence(reliable), REMEDIATION);
    }

    private static String calculateConfidence(boolean reliable) {
        String confidence = "Tentative";
        if (reliable) {
            confidence = "Firm";
        }
        return confidence;
    }

}

class InputTransformation extends CustomScanIssue {
    private final static String NAME = "Suspicious input transformation";
    private final static String DETAIL = "The application transforms input in a way that suggests it might be vulnerable to some kind of server-side code injection";
    private final static String REMEDIATION =
            "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. " +
                    "Try to determine the root cause of the observed input transformations. " +
                    "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results.";
    private final static String CONFIDENCE = "Tentative";

    InputTransformation(ArrayList<String> interesting, ArrayList<String> boring, IHttpRequestResponse base, URL url, String paramName) {
        super(base.getHttpService(), url, new IHttpRequestResponse[]{base}, NAME, generateDetail(interesting, boring, paramName), generateSeverity(interesting), CONFIDENCE, REMEDIATION);
    }

    private static String generateSeverity(ArrayList<String> interesting) {
        String severity = "High";
        if (interesting.size() == 1 && interesting.contains("\\0 => \0")) {
            severity = "Information";
        }
        return severity;
    }

    private static String generateDetail(ArrayList<String> interesting, ArrayList<String> boring, String paramName) {
        String details = DETAIL + "<br/><br/>Affected parameter:<code>" + StringEscapeUtils.escapeHtml4(paramName) + "</code><br/><br/>";
        details += "<p>Interesting transformations:</p><ul> ";
        for (String transform : interesting) {
            details += "<li><b><code style='font-size: 125%;'>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul><p>Boring transformations:</p><ul>";
        for (String transform : boring) {
            details += "<li><b><code>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul>";
        return details;
    }
}

class ParamInsertionPoint implements IScannerInsertionPoint {
    byte[] request;
    String name;
    String value;
    byte type;

    ParamInsertionPoint(byte[] request, String name, String value, byte type) {
        this.request = request;
        this.name = name;
        this.value = value;
        this.type = type;
    }

    @Override
    public String getInsertionPointName() {
        return name;
    }

    @Override
    public String getBaseValue() {
        return value;
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return Utilities.helpers.updateParameter(request, newParam);
    }

    @Override
    public int[] getPayloadOffsets(byte[] payload) {
        //IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return new int[]{0, 0};
        //return new int[]{newParam.getValueStart(), newParam.getValueEnd()};
    }

    @Override
    public byte getInsertionPointType() {
        return type;
        //return IScannerInsertionPoint.INS_PARAM_BODY;
        // return IScannerInsertionPoint.INS_EXTENSION_PROVIDED;
    }
}

class ParamNameInsertionPoint extends ParamInsertionPoint {

    public ParamNameInsertionPoint(byte[] request, String name, String value, byte type) {
        super(request, name, value, type);
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        IParameter newParam = Utilities.helpers.buildParameter(Utilities.helpers.bytesToString(payload), Utilities.encodeParam(value), type);
        return Utilities.helpers.updateParameter(request, newParam);
    }
}

class JsonParamNameInsertionPoint extends ParamInsertionPoint {
    byte[] headers;
    byte[] body;

    public JsonParamNameInsertionPoint(byte[] request, String name, String value, byte type) {
        super(request, name, Utilities.encodeJSON(value), type);
        int start = Utilities.getBodyStart(request);
        headers = Arrays.copyOfRange(request, 0, start);
        body = Arrays.copyOfRange(request, start + 1, request.length);
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(headers);
            outputStream.write('{');
            outputStream.write('"');
            outputStream.write(payload);
            outputStream.write(Utilities.helpers.stringToBytes("\":\"" + Utilities.helpers.bytesToString(payload) + value + "\","));
            outputStream.write(body);
            return Utilities.fixContentLength(outputStream.toByteArray());
        }
        catch (IOException e) {
            throw new RuntimeException("Request creation unexpectedly failed");
        }
    }
}


class CustomScanIssue implements IScanIssue {
    private IHttpService httpService;
    private URL url;
    private IHttpRequestResponse[] httpMessages;
    private String name;
    private String detail;
    private String severity;
    private String confidence;
    private String remediation;

    CustomScanIssue(
            IHttpService httpService,
            URL url,
            IHttpRequestResponse[] httpMessages,
            String name,
            String detail,
            String severity,
            String confidence,
            String remediation) {
        this.name = name;
        this.detail = detail;
        this.severity = severity;
        this.httpService = httpService;
        this.url = url;
        this.httpMessages = httpMessages;
        this.confidence = confidence;
        this.remediation = remediation;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public String getIssueName() {
        return name;
    }

    @Override
    public int getIssueType() {
        return 0;
    }

    @Override
    public String getSeverity() {
        return severity;
    }

    @Override
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String getIssueBackground() {
        return null;
    }

    @Override
    public String getRemediationBackground() {
        return null;
    }

    @Override
    public String getIssueDetail() {
        return detail;
    }

    @Override
    public String getRemediationDetail() {
        return remediation;
    }

    @Override
    public IHttpRequestResponse[] getHttpMessages() {
        return httpMessages;
    }

    @Override
    public IHttpService getHttpService() {
        return httpService;
    }

    public String getHost() {
        return null;
    }

    public int getPort() {
        return 0;
    }

    public String getProtocol() {
        return null;
    }
}

class OfferParamGuess implements IContextMenuFactory {
    private IBurpExtenderCallbacks callbacks;

    public OfferParamGuess(final IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        IHttpRequestResponse[] reqs = invocation.getSelectedMessages();
        List<JMenuItem> options = new ArrayList<>();
        JMenuItem probeButton = new JMenuItem("Guess GET parameters");
        probeButton.addActionListener(new TriggerParamGuesser(reqs, false, IParameter.PARAM_URL));
        options.add(probeButton);

        if (reqs.length == 1) {
            IHttpRequestResponse req = reqs[0];
            byte[] resp = req.getRequest();
            if (Utilities.countMatches(resp, Utilities.helpers.stringToBytes("%253c%2561%2560%2527%2522%2524%257b%257b%255c")) > 0) {
                JMenuItem backendProbeButton = new JMenuItem("*Identify backend parameters*");
                backendProbeButton.addActionListener(new TriggerParamGuesser(reqs, true, IParameter.PARAM_URL));
                options.add(backendProbeButton);
            }

            if (resp[0] == 'P') {
                IRequestInfo info = Utilities.helpers.analyzeRequest(req);
                List<IParameter> params = info.getParameters();

                HashSet<Byte> paramTypes = new HashSet<>();
                for (IParameter param : params) {
                    if (param.getType() != IParameter.PARAM_URL) {
                        paramTypes.add(param.getType());
                    }
                }

                for (Byte type : paramTypes) {
                    JMenuItem postProbeButton = new JMenuItem("Guess body parameter: " + type);
                    postProbeButton.addActionListener(new TriggerParamGuesser(reqs, false, type));
                    options.add(postProbeButton);
                }
            }
        }

        return options;
    }
}

class TriggerParamGuesser implements ActionListener {
    private IHttpRequestResponse[] reqs;
    private boolean backend;
    private byte type;

    TriggerParamGuesser(IHttpRequestResponse[] reqs, boolean backend, byte type) {
        this.backend = backend;
        this.reqs = reqs;
        this.type = type;
    }

    public void actionPerformed(ActionEvent e) {
        for (IHttpRequestResponse req: reqs) {
            Runnable runnable = new ParamGuesser(req, backend, type);
            (new Thread(runnable)).start();
        }
    }
}

class ParamGuesser implements Runnable, IExtensionStateListener {

    private IHttpRequestResponse req;
    private boolean backend;
    private byte type;

    public ParamGuesser(IHttpRequestResponse req, boolean backend, byte type) {
        this.req = req;
        this.backend = backend;
        this.type = type;
    }

    public void run() {

        IRequestInfo info = Utilities.helpers.analyzeRequest(req);
        List<IParameter> params = info.getParameters();

        if(backend) {

            for (IParameter param : params) {
                String key = null;
                String[] keys = {"%26zq=%253c", "!zq=%253c"};
                for (String test : keys) {
                    if (param.getValue().contains(test)) {
                        key = test;
                        break;
                    }
                }

                if (key != null) {
                    String originalValue = param.getValue().substring(0, param.getValue().indexOf(key));
                    ParamInsertionPoint insertionPoint = new ParamInsertionPoint(req.getRequest(), param.getName(), originalValue, param.getType());
                    ArrayList<Attack> paramGuesses = guessBackendParams(req, insertionPoint);
                    if (!paramGuesses.isEmpty()) {
                        Utilities.callbacks.addScanIssue(Utilities.reportReflectionIssue(paramGuesses.toArray((new Attack[paramGuesses.size()])), req));
                    }
                    break;
                }

            }
        }
        else {
            ArrayList<Attack> paramGuesses = guessParams(req, type);
            if (!paramGuesses.isEmpty()) {
                Utilities.callbacks.addScanIssue(Utilities.reportReflectionIssue(paramGuesses.toArray((new Attack[paramGuesses.size()])), req));
            }
        }
    }

    public void extensionUnloaded() {
        Utilities.out("Aborting param bruteforce");
        Utilities.unloaded.set(true);
    }

    static ArrayList<String> getParamsFromResponse(IHttpRequestResponse baseRequestResponse) {
        byte[] resp = baseRequestResponse.getResponse();
        int bodyStart = Utilities.getBodyStart(resp);
        String body = Utilities.helpers.bytesToString(Arrays.copyOfRange(resp, bodyStart, resp.length));
        ArrayList<String> found = new ArrayList<>();
        try {
            GsonBuilder builder = new GsonBuilder();
            Map parsed = builder.create().fromJson(body, Map.class);
            Utilities.out("Analysed response params: ");
            for (Object key : parsed.keySet()) {
                found.add(key.toString());
            }
        }
        catch (Exception e) {

        }

        return found;
    }

    static ArrayList<Attack> guessParams(IHttpRequestResponse baseRequestResponse, byte type) {
        ArrayList<Attack> attacks = new ArrayList<>();
        String targetURL = baseRequestResponse.getHttpService().getHost();

        IRequestInfo info = Utilities.helpers.analyzeRequest(baseRequestResponse.getRequest());
        List<IParameter> currentParams = info.getParameters();

        HashSet<String> witnessedParams = new HashSet<>();
        for (IParameter param : currentParams) {
            if (param.getType() == type) {
                witnessedParams.add(param.getName());
            }
        }

        ArrayList<String> params = getParamsFromResponse(baseRequestResponse);
        params.addAll(Utilities.paramNames);

        try {
            final String payload = "qn<a`'\\\"${{\\\\";

            IScannerInsertionPoint insertionPoint = getInsertionPoint(baseRequestResponse, type, payload);

            PayloadInjector injector = new PayloadInjector(baseRequestResponse, insertionPoint);

            Utilities.out("Initiating parameter name bruteforce on "+ targetURL);
            Attack base = getBaselineAttack(injector);

            for (int i = 0; i < 1000; i++) { // params.size()
                String candidate = params.get(i);
                if (witnessedParams.contains(candidate)) {
                    continue;
                }

                Attack paramGuess = injector.buildAttack(candidate, false);
                if (!Utilities.similar(base, paramGuess)) {
                    //Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), baseRequestResponse.getRequest()); // rest to base
                    Attack confirmParamGuess = injector.buildAttack(candidate, false);
                    Attack failAttack = injector.buildAttack(candidate + "z", false);
                    base.addAttack(failAttack);
                    if (!Utilities.similar(base, confirmParamGuess)) {
                        Probe validParam = new Probe(targetURL+" found param: " + candidate, 4, candidate);
                        validParam.setEscapeStrings(Utilities.randomString(candidate.length()), candidate + "z");
                        validParam.setRandomAnchor(false);
                        validParam.setPrefix(Probe.REPLACE);
                        ArrayList<Attack> confirmed = injector.fuzz(base, validParam);
                        if (!confirmed.isEmpty()) {
                            Utilities.out(targetURL+" identified parameter: " + candidate);
                            attacks.addAll(confirmed);
                        }
                        else {
                            Utilities.out(targetURL+" failed to confirm: "+candidate);
                        }
                    } else {
                        // we can use stored input reflection - the input is rejected, the other technique will work
                        byte[] failResp = failAttack.getFirstRequest().getResponse();
                        if(Utilities.getMatches(failResp, Utilities.helpers.stringToBytes(candidate+"qn"), failResp.length).size() > 0) {
                            Utilities.out(targetURL+" identified persistent parameter: " + candidate);
                            // todo report a scanner issue for this
                            base = getBaselineAttack(injector); // re-benchmark
                        }
                        else {
                            Utilities.out(targetURL + " couldn't replicate: " + candidate);
                            base.addAttack(paramGuess);
                        }
                    }
                }

            }
            Utilities.out("Parameter name bruteforce complete: "+targetURL);

        }
        catch (RuntimeException e) {
            Utilities.out("Parameter name bruteforce aborted: "+targetURL);
        }

        return attacks;
    }

    private static Attack getBaselineAttack(PayloadInjector injector) {
        Attack base = injector.buildAttack(Utilities.randomString(6), false);
        for(int i=0; i<4; i++) {
            base.addAttack(injector.buildAttack(Utilities.randomString((i+1)*(i+1)), false));
        }
        return base;
    }

    private static IScannerInsertionPoint getInsertionPoint(IHttpRequestResponse baseRequestResponse, byte type, String payload) {
        return type == IParameter.PARAM_JSON ?
                        new JsonParamNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type) :
                        new ParamNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type);
    }

    static ArrayList<Attack> guessBackendParams(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {

        String baseValue = insertionPoint.getBaseValue();
        PayloadInjector injector = new PayloadInjector(baseRequestResponse, insertionPoint);
        String targetURL = baseRequestResponse.getHttpService().getHost();
        Utilities.out("Initiating parameter name bruteforce on "+ targetURL);
        final String breaker = "=%3c%61%60%27%22%24%7b%7b%5c";
        Attack base = injector.buildAttack(baseValue+"&"+Utilities.randomString(6)+ breaker, false);

        for(int i=0; i<4; i++) {
            base.addAttack(injector.buildAttack(baseValue+"&"+Utilities.randomString((i+1)*(i+1))+ breaker, false));
        }

        ArrayList<Attack> attacks = new ArrayList<>();
        try {
            for (int i = 0; i < Utilities.paramNames.size(); i++) { // i<Utilities.paramNames.size();
                String candidate = Utilities.paramNames.get(i);
                Attack paramGuess = injector.buildAttack(baseValue + "&" + candidate + breaker, false);
                if (!Utilities.similar(base, paramGuess)) {
                    Attack confirmParamGuess = injector.buildAttack(baseValue + "&" + candidate + breaker, false);
                    base.addAttack(injector.buildAttack(baseValue + "&" + candidate + "z"+breaker, false));
                    if (!Utilities.similar(base, confirmParamGuess)) {
                        Probe validParam = new Probe("Backend param: " + candidate, 4, "&" + candidate + breaker, "&" + candidate + "=%3c%62%60%27%22%24%7b%7b%5c");
                        validParam.setEscapeStrings("&" + Utilities.randomString(candidate.length()) + breaker, "&" + candidate + "z"+breaker);
                        validParam.setRandomAnchor(false);
                        ArrayList<Attack> confirmed = injector.fuzz(base, validParam);
                        if (!confirmed.isEmpty()) {
                            Utilities.out("Identified backend parameter: " + candidate);
                            attacks.addAll(confirmed);
                        }
                    } else {
                        base.addAttack(paramGuess);
                    }
                }

            }
            Utilities.out("Parameter name bruteforce complete: "+targetURL);
        }
        catch (RuntimeException e) {
            Utilities.out("Parameter name bruteforce aborted: "+targetURL);
        }

        return attacks;
    }

}
