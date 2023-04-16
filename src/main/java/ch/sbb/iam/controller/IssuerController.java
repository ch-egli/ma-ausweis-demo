package ch.sbb.iam.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.*;
import java.util.stream.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.cache.annotation.*;
import com.github.benmanes.caffeine.cache.*;
import com.microsoft.aad.msal4j.*;

@RestController
@EnableCaching
public class IssuerController {
    private static final Logger lgr = Logger.getLogger(IssuerController.class.getName());

    private final Cache<String, String> cache = Caffeine.newBuilder()
                                            .expireAfterWrite(15, TimeUnit.MINUTES)
                                            .maximumSize(100)
                                            .build();

    // *********************************************************************************
    // application properties - from envvars
    // *********************************************************************************
    @Value("${aadvc_TenantId}")
    private String tenantId;

    @Value("${aadvc_scope}")
    private String scope;

    @Value("${aadvc_ApiKey}")
    private String apiKey;

    @Value("${aadvc_ClientId}")
    private String clientId;

    @Value("${aadvc_ClientSecret}")
    private String clientSecret;

    @Value("${aadvc_ApiEndpoint}")
    private String apiEndpoint;

    @Value("${aadvc_Authority}")
    private String aadAuthority;

    @Value("${aadvc_IssuerAuthority}")
    private String issuerAuthority;

    @Value("${aadvc_CredentialManifest}")
    private String credentialManifest;

    private final static String issuerBaseRequest = """
        {
          "includeQRCode": true,
          "callback": {
            "url": "",
            "state": "STATEWILLBESETINCODE",
            "headers": {
              "api-key": "OPTIONAL API-KEY for ISSUANCE CALLBACK API"
            }
          },
          "authority": "",
          "registration": {
            "clientName": "Snoopfish Community Member Issuer",
            "purpose": "Please accept the card to prove you are a Snoopfish community member"
        },
          "type": "SnoopfishCommunityMember",
          "manifest": "",
          "pin": {
            "value": "PIN_IS_SET_IN_CODE",
            "length": 4
          },
          "claims": {
            "given_name": "FIRSTNAME",
            "family_name": "LASTNAME"
          }
        }""";

    // *********************************************************************************
    // helpers
    // *********************************************************************************
    public static String getBasePath(HttpServletRequest request) {
        String basePath = "https://" + request.getServerName() + "/";
        return basePath;
    }

    public static void traceHttpRequest( HttpServletRequest request ) {
        String method = request.getMethod();
        String requestURL = request.getRequestURL().toString();
        String queryString = request.getQueryString();
        if (queryString != null) {
            requestURL += "?" + queryString;
        }
        
        lgr.info( method + " " + requestURL );
    }

    private String base64Decode( String base64String ) {
        if ( (base64String.length()%4) > 0  ) {
            base64String += "====".substring((base64String.length()%4));
        }
        return new String(Base64.getUrlDecoder().decode(base64String), StandardCharsets.UTF_8);
    } 

    private static String readFileAllText(String filePath)
    {
        StringBuilder contentBuilder = new StringBuilder();
        try (Stream<String> stream = Files.lines( Paths.get(filePath), StandardCharsets.UTF_8)) {
            stream.forEach(s -> contentBuilder.append(s).append("\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return contentBuilder.toString();
    }

    private String callVCClientAPI( String payload ) {
        String accessToken = "";
        try {
            accessToken = cache.getIfPresent( "MSALAccessToken" );
            if ( accessToken == null || accessToken.isEmpty() ) {
                accessToken = getMSALAccessToken(); //ByClientCredentialGrant();
                lgr.info( accessToken );
                cache.put( "MSALAccessToken", accessToken );
            }
        } catch( Exception ex ) {
            ex.printStackTrace();
            return null;
        }
        String endpoint = apiEndpoint.replace("http://", "https://" ) + "verifiableCredentials/createIssuanceRequest";
        lgr.info( "callVCClientAPI: " + endpoint + "\n" + payload );
        WebClient client = WebClient.create();
        WebClient.ResponseSpec responseSpec = client.post()
                                                    .uri( endpoint )
                                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                                    .header("Authorization", "Bearer " + accessToken)
                                                    .accept(MediaType.APPLICATION_JSON)
                                                    .body(BodyInserters.fromObject(payload))
                                                    .retrieve();
        String responseBody = responseSpec.bodyToMono(String.class).block();
        lgr.info( responseBody );
        return responseBody;
    }

    private String downloadManifest( String manifestURL ) {
        lgr.info( "manifestURL: " + manifestURL );
        WebClient client = WebClient.create();
        WebClient.ResponseSpec responseSpec = client.get()
                                                    .uri( manifestURL )
                                                    .accept(MediaType.APPLICATION_JSON)
                                                    .retrieve();
        String responseBody = responseSpec.bodyToMono(String.class).block();
        lgr.info( responseBody );
        return responseBody;
    }

    public String generatePinCode( Integer length ) {
        int min = 0;
        int max = (int)(Integer.parseInt( "999999999999999999999".substring(0, length) ));
        Integer pin = (Integer)(int)((Math.random() * (max - min)) + min);
        return String.format( String.format("%%0%dd", length), pin );
    }

    private String getMSALAccessToken() throws Exception {
        String authority = aadAuthority.replace("{0}", tenantId );
        lgr.info( aadAuthority );
        lgr.info( authority );
        ConfidentialClientApplication app = null;
        if ( !clientSecret.isEmpty() ) {
            lgr.info( "MSAL Acquire AccessToken via Client Credentials" );
            app = ConfidentialClientApplication.builder(
                clientId,
                ClientCredentialFactory.createFromSecret(clientSecret))
                .authority(authority)
                .build();
        } else {
            lgr.info( "MSAL Acquire AccessToken via Certificate" );
            lgr.log(Level.SEVERE, "Token acquisition via certificate not implemented...");
            /*
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(certKeyLocation)));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(spec);
            java.io.InputStream certStream = (java.io.InputStream)new ByteArrayInputStream(Files.readAllBytes(Paths.get(certLocation)));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certStream);
            app = ConfidentialClientApplication.builder(
                   clientId,
                   ClientCredentialFactory.createFromCertificate(key, cert))
                   .authority(authority)
                   .build();
             */
        }
        ClientCredentialParameters clientCredentialParam = ClientCredentialParameters.builder(
                Collections.singleton(scope))
                .build();
        CompletableFuture<IAuthenticationResult> future = app.acquireToken(clientCredentialParam);
        IAuthenticationResult result = future.get();
        return result.accessToken();
    }

    /**
     * This method is called from the UI to initiate the issuance of the verifiable credential
     * @param request
     * @param headers
     * @return JSON object with the address to the presentation request and optionally a QR code and a state value which can be used to check on the response status
     */
    @GetMapping("/api/issuer/issuance-request")
    public ResponseEntity<String> issueRequest( HttpServletRequest request, @RequestHeader HttpHeaders headers ) {
        traceHttpRequest( request );
        // payload is loaded from file and then partly modified here
        String jsonRequest = issuerBaseRequest;

        String callback = getBasePath( request ) + "api/issuer/issue-request-callback";
        String correlationId = java.util.UUID.randomUUID().toString();
        ObjectMapper objectMapper = new ObjectMapper();
        String payload = "{}";
        Integer pinCodeLength = 0;
        String pinCode = null;
        String responseBody = "";
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("status", "request_created" );
            data.put("message", "Waiting for QR code to be scanned" );
            cache.put( correlationId, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data) );
        
            JsonNode rootNode = objectMapper.readTree( jsonRequest );
            if (fromMobile(request)) {
                ((ObjectNode)rootNode).remove("pin");
            }
            ((ObjectNode)rootNode).put("authority", issuerAuthority );
            // modify the callback method to make it easier to debug
            // with tools like ngrok since the URI changes all the time
            // this way you don't need to modify the callback URL in the payload every time
            // ngrok changes the URI
            ((ObjectNode)(rootNode.path("callback"))).put("url", callback );
            // modify payload with new state, the state is used to be able to update the UI when callbacks are received from the VC Service
            ((ObjectNode)(rootNode.path("callback"))).put("state", correlationId );
            // set our api-key so we check that callbacks are legitimate
            ((ObjectNode)(rootNode.path("callback").path("headers"))).put("api-key", apiKey );
            // get the manifest from the application.properties (envvars), this is the URL to the credential created in the azure portal.
            // the display and rules file to create the credential can be dound in the credentialfiles directory
            // make sure the credentialtype in the issuance payload ma
            ((ObjectNode)rootNode).put("manifest", credentialManifest );
            // check if pin is required, if found make sure we set a new random pin
            // pincode is only used when the payload contains claim value pairs which results in an IDTokenhint
            if ( rootNode.has("pin") ) {
                pinCodeLength = rootNode.path("pin").path("length").asInt();
                // don't use pin if user is on mobile device
                String userAgent = request.getHeader("user-agent");
                if ( pinCodeLength <= 0 || userAgent.contains("Android") || userAgent.contains("iPhone") ) {
                    ((ObjectNode)rootNode).remove("pin");
                } else {
                    pinCode = generatePinCode( pinCodeLength );
                    ((ObjectNode)(rootNode.path("pin"))).put("value", pinCode );
                }
            }
            // here you could change the payload manifest and change the firstname and lastname. The fieldNames should match your Rules definition
            if ( rootNode.has("claims") ) {
                ObjectNode claims = ((ObjectNode)rootNode.path("claims"));
                if ( claims.has("given_name") ) {
                    claims.put("given_name", "Christian" );
                }
                if ( claims.has("family_name") ) {
                    claims.put("family_name", "Egli" );
                }
            }
            // The VC Request API is an authenticated API. We need to clientid and secret to create an access token which
            // needs to be send as bearer to the VC Request API
            payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
            responseBody = callVCClientAPI( payload );
            JsonNode apiResponse = objectMapper.readTree( responseBody );
            ((ObjectNode)apiResponse).put( "id", correlationId );
            if ( pinCodeLength > 0 ) {
                ((ObjectNode)apiResponse).put( "pin", pinCode );
            }
            responseBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(apiResponse);
        } catch (java.io.IOException ex) {
            ex.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Technical error" );
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        return ResponseEntity.ok()
          .headers(responseHeaders)
          .body( responseBody );
    }

    private boolean fromMobile(HttpServletRequest request) {
        String userAgent = Optional.ofNullable(request.getHeader(HttpHeaders.USER_AGENT)).orElse("").toLowerCase(Locale.ROOT);
        return  userAgent.contains("android") || userAgent.contains("iphone");
    }

    /**
     * This method is called by the VC Request API when the user scans a QR code and presents a Verifiable Credential to the service
     * @param request
     * @param headers
     * @param body
     * @return
     */
    @RequestMapping(value = "/api/issuer/issue-request-callback", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
    public ResponseEntity<String> issueRequestCallback( HttpServletRequest request
                                                      , @RequestHeader HttpHeaders headers
                                                      , @RequestBody String body ) {
        traceHttpRequest( request );
        lgr.info( body );
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // we need to get back our api-key in the header to make sure we don't accept unsolicited calls
            if ( !request.getHeader("api-key").equals(apiKey) ) {
                lgr.info( "api-key wrong or missing" );
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body( "api-key wrong or missing" );
            }
            JsonNode issuanceResponse = objectMapper.readTree( body );
            String requestStatus = issuanceResponse.path("requestStatus").asText();
            ObjectNode data = null;
            // there are 2 different callbacks. 1 if the QR code is scanned (or deeplink has been followed)
            // Scanning the QR code makes Authenticator download the specific request from the server
            // the request will be deleted from the server immediately.
            // That's why it is so important to capture this callback and relay this to the UI so the UI can hide
            // the QR code to prevent the user from scanning it twice (resulting in an error since the request is already deleted)
            if ( requestStatus.equals( "request_retrieved" )  ) {
                data = objectMapper.createObjectNode();
                data.put("message", "QR Code is scanned. Waiting for issuance to complete..." );
            }
            if ( requestStatus.equals("issuance_successful") ) {
                data = objectMapper.createObjectNode();
                data.put("message", "Credential successfully issued" );
            }
            if ( requestStatus.equals( "issuance_error" ) ) {
                data = objectMapper.createObjectNode();
                data.put("message", issuanceResponse.path("error").path("message").asText() );
            }
            if ( data != null ) {
                String id = issuanceResponse.path("state").asText();
                String dataChk = cache.getIfPresent( id ); // id == correlationId
                if ( dataChk == null ) {
                    lgr.info( "Unknown state: " + id );
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Unknown state" );
                } else {
                    data.put("status", requestStatus );
                    cache.put( id, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data) );
                }                
            } else {
                lgr.info( "Unsupported requestStatus" );
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Unsupported requestStatus" );
            }
        } catch (java.io.IOException ex) {
            ex.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Technical error" );
        }
        return ResponseEntity.ok()
          .body( "{}" );
    }

    /**
     * this function is called from the UI polling for a response from the AAD VC Service.
     * when a callback is recieved at the presentationCallback service the session will be updated
     * this method will respond with the status so the UI can reflect if the QR code was scanned and with the result of the presentation
     * @param request
     * @param headers
     * @param id the correlation id that was set in the state attribute in the payload
     * @return response to the browser on the progress of the issuance
     */
    @GetMapping("/api/issuer/issuance-response")
    public ResponseEntity<String> issueResponseStatus( HttpServletRequest request
                                                            , @RequestHeader HttpHeaders headers
                                                            , @RequestParam String id ) {
        traceHttpRequest( request );
        String responseBody = "";
        String data = cache.getIfPresent( id ); // id == correlationId/state
        if ( !(data == null || data.isEmpty()) ) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode cacheData = objectMapper.readTree( data  );
                ObjectNode statusResponse = objectMapper.createObjectNode();
                statusResponse.put("status", cacheData.path("status").asText() );
                statusResponse.put("message", cacheData.path("message").asText() );
                responseBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(statusResponse);
            } catch (java.io.IOException ex) {
                ex.printStackTrace();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Technical error" );
            }
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        return ResponseEntity.ok()
          .headers(responseHeaders)
          .body( responseBody );
    }

    @GetMapping("/api/issuer/get-manifest")
    public ResponseEntity<String> getManifest( HttpServletRequest request
                                            , @RequestHeader HttpHeaders headers ) {
        traceHttpRequest( request );
        String manifest = cache.getIfPresent( "manifest" );
        if ( manifest == null ) {
            String responseBody = downloadManifest(credentialManifest);
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode resp = objectMapper.readTree( responseBody );
                manifest = base64Decode( resp.path("token").asText().split("\\.")[1] );
                cache.put( "manifest", manifest );
            } catch (java.io.IOException ex) {
                ex.printStackTrace();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body( "Technical error" );
            }
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        return ResponseEntity.ok()
          .headers(responseHeaders)
          .body( manifest );
    }

} // cls
