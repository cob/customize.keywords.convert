import groovy.transform.Field
import org.codehaus.jettison.json.JSONObject

import com.google.common.cache.*
import java.util.concurrent.TimeUnit

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Base64;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity
import javax.ws.rs.client.ClientBuilder;
import org.glassfish.jersey.media.multipart.*;
import org.glassfish.jersey.media.multipart.file.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;

import org.apache.commons.io.FileUtils;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import config.ConvertToConfig
import java.nio.charset.StandardCharsets;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

//log.info("DAN. AUTOPDF.GROOVY ONNN ddsfds !!! '$msg.type'")
log.info("STARTING _convertTo.groovy")

if (msg.product != "recordm-definition" && msg.product != "recordm") {
    return
}

@Field static cacheOfConvertFieldsForDefinition = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();

if (msg.product == "recordm-definition") cacheOfConvertFieldsForDefinition.invalidate(msg.type)

// ========================================================================================================
def convertFields = cacheOfConvertFieldsForDefinition.get(msg.type, { getConversionFields(msg.type) })
if (msg.user != "integrationm" && convertFields.size() > 0 && msg.product == "recordm" && msg.action =~ "add|update") {
    def updatedFields = getConversionFieldsUpdates(convertFields, msg.instance.fields)
    log.info("[_\$convert] Updating fields for instance ${msg.instance.id} updatedFields=${updatedFields}")
    recordm.update(msg.type, msg.instance.id, updatedFields);
}

// ========================================================================================================
def getConversionFieldsUpdates(auditFields,instanceFields) {
    def updates = [:]
    log.info("IN getConversionFieldsUpdates. Fields: ${auditFields.size()}")
    auditFields.each { auditField ->

        def currentValue = msg.value(auditField.sourceField)
        if( ( msg.action == 'add' || (msg.action == 'update' && msg.field(auditField.sourceField).changed()) )
        && currentValue != null ) {
            try {
                if ("file".equals(auditField.cType) ) {
                    def excelMatcher = currentValue =~  /(.*)\.([A-Za-z]+)/
                    if(excelMatcher.size() > 0 && ConvertToConfig.SUPPORTED_INPUTS.contains(excelMatcher[0][2].toUpperCase())){
                        String filename = excelMatcher[0][1]; //currentValue.split("\\.")[0];
                        def absPath = "/var/lib/recordm/attachments/${msg.getInstance().field(auditField.sourceField).getFilePath()}/${currentValue}"
                        def destPath = "/tmp/${filename}.pdf"
                        File f = new File(absPath);
                        long len = f.length();
                        if(f.exists()){
                            if(convertFile(f,filename,excelMatcher[0][2],destPath,msg.instance.id,auditField.name)){
                                currentValue = filename+".pdf"
                                log.info("value '$currentValue'")
                                updates << [(auditField.name) : currentValue]
                            }else{
                                log.error("FAILED TO CONVERT ${absPath} TO PDF")
                            }
                        }else{
                            log.error("FILE ${absPath} NOT FOUND")
                        }
                    }else{
                        log.error("EXPECTED EXTENSION '${ConvertToConfig.SUPPORTED_INPUTS}'. GOT '${currentValue}'")
                    }
                }else{
                    def filename = System.currentTimeMillis()+""+auditField.cType
                    def destPath = "/tmp/${filename}.pdf"

                    if(convertText(currentValue,"${filename}.${auditField.cType}",destPath,msg.instance.id,auditField.name,auditField.cType)){
                        log.info("CONVERTED ${auditField.cType} TO PDF")
                        currentValue = filename+".pdf"
                        updates << [(auditField.name) : currentValue]
                    }else{
                        log.error("${auditField.cType} CONVERSION DID NOT HAPPEN")
                    }
                }
            }catch(Exception e){
                e.printStackTrace()
                log.error("${e.getMessage()}");
            }

        }
    }
    log.info("[\$convert.pdf] Update 'convertPDFS' for updates: '$updates'");
    return updates
}
def supportedDollarDescription(descriptionName){
    if( ( descriptionName =~ /[$]text/).size() > 0){
        return "txt"; //CONVERT TEXT
    }else if((descriptionName =~ /[$]markdown/).size() > 0) {
        return "html" //CONVERT FILE
    }else if( ( descriptionName =~ /[$]file/).size() > 0){
        return "file"
    }else{
        return null
    }
}
def getConversionFields(definitionName) {
    log.info("IN getConversionFields")
    // Obtém detalhes da definição
    def definitionEncoded = URLEncoder.encode(definitionName, "utf-8").replace("+", "%20")
    def resp = actionPacks.rmRest.get( "recordm/definitions/name/${definitionEncoded}".toString(), [:], "integrationm");
    JSONObject definition = new JSONObject(resp);

    def fieldsSize = definition.fieldDefinitions.length();

    def fields = [:]
    (0..fieldsSize-1).each { index ->
        def fieldDefinition  = definition.fieldDefinitions.getJSONObject(index)
        def fieldDescription = fieldDefinition.optString("description")
        def convertionType = supportedDollarDescription(fieldDescription)
        if(fieldDescription  &&  convertionType != null){
            def fieldDefId       = fieldDefinition.get("id");
            def fieldName        = fieldDefinition.get("name");
            fields[fieldName]   = [name:fieldName, description: fieldDescription, fieldId:fieldDefId, cType:convertionType]
        }
    }

    // Finalmente obtém a lista de campos que é necessário calcular
    def convertionFields = [];
    fields.each { fieldName,field ->
        def matcher = field.description =~ /[$]convert\(([^)].*)\)\.pdf/
        if( matcher.size() > 0) {
            def sourceField = matcher[0][1]
            if(fields[sourceField] && "file".equals(field.cType)){
                convertionFields << [fieldId: field.fieldId, name:field.name, sourceField:sourceField, cType:fields[sourceField].cType]
            }
        }
    }
    log.info("[\$convert.pdf] Update 'convertFields' for '$definitionName': $convertionFields");
    return convertionFields
}

def convertFile(File file,String filename, String fileExtension,String destPath,int instanceId, String fieldName){

    def String TARGET_URL="https://v2.convertapi.com/convert/${fileExtension}/to/pdf?Secret=${ConvertToConfig.API_KEY}";
    log.info("TARGET: ${TARGET_URL}")
    Client client = ClientBuilder.newClient()
        .property(ClientProperties.CONNECT_TIMEOUT, 3000)
        .property(ClientProperties.READ_TIMEOUT, 90000)
        .register(JacksonFeature.class)
        .register(MultiPartFeature.class);
    WebTarget webTarget = client.target(TARGET_URL);

    MultiPart multiPart = new MultiPart();
    multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);

    FileDataBodyPart fileDataBodyPart = new FileDataBodyPart("File",file,MediaType.valueOf("application/pdf"));
    multiPart.bodyPart(fileDataBodyPart);

    multiPart.bodyPart(new FormDataBodyPart( "StoreFile", "true"));
    multiPart.bodyPart(new FormDataBodyPart( "filename",filename));
    multiPart.bodyPart(new FormDataBodyPart( "WorksheetIndex","1"));

    Response response = webTarget.request(MediaType.APPLICATION_JSON).post(Entity.entity(multiPart, multiPart.getMediaType()));

    String url = getURLFromResponse(response);

    log.info ("Finished convertion. STATUS: ${response.getStatus()} URL ${url}")
    return downloadFile(response.getStatus(),destPath,client,url,instanceId,fieldName);
}

def convertText(String text,String filename, String destPath, int instanceId, String fieldName, String fileExtension){
    def userCSS = "";
    if("html".equals(fileExtension)){
        text = markdownToHTML(text)
        userCSS = ",{\"Name\":\"UserCss\",\"Value\":\""+ConvertToConfig.MD_CSS_STYLE+"\"}";
    }

    String base64Text = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    String request = "{" +
                "    \"Parameters\": [" +
                "        {\n" +
                "            \"Name\": \"File\"," +
                "            \"FileValue\": {" +
                "                \"Name\": \""+filename+"\"," +
                "                \"Data\": \"" +base64Text+ "\""+
                "            }" +
                "        }," +
                "        {" +
                "            \"Name\": \"StoreFile\"," +
                "            \"Value\": true\n" +
                "        }," +
                "        {" +
                "            \"Name\": \"Scale\"," +
                "            \"Value\": 175" +
                "        }" +userCSS+
                "    ]" +
                "}";
    def String TARGET_URL="https://v2.convertapi.com/convert/${fileExtension}/to/pdf?Secret=${ConvertToConfig.API_KEY}";
    log.info("TARGEET : ${TARGET_URL}")
    Client client = ClientBuilder.newClient()
        .property(ClientProperties.CONNECT_TIMEOUT, 3000)
        .property(ClientProperties.READ_TIMEOUT, 90000);

    WebTarget webTarget = client.target(TARGET_URL);

    Response response = webTarget.request(MediaType.APPLICATION_JSON).post(Entity.entity(request,MediaType.APPLICATION_JSON));

    log.info ("Finished text-to-file convertion. STATUS: ${response.getStatus()}")

    String url = getURLFromResponse(response);

    return downloadFile(response.getStatus(),destPath,client,url,instanceId,fieldName);
}
def getURLFromResponse(response){
    String jsonResponse = response.readEntity(String.class);
    JSONObject jsonObject = new JSONObject(jsonResponse);
    if(response.getStatus()==200){
        JSONArray jsonArray = jsonObject.getJSONArray("Files");
        return jsonArray.getJSONObject(0).getString("Url");
    }else{
        String message = jsonObject.getString("Message");
        log.error("HTTPSTATUS:${response.getStatus()} . ${message}")
        return "";
    }
}
def downloadFile(status,destPath,client,url,instanceId,fieldName){
    if(200 == status){
        File fileToDownloaded;
        try{
            fileToDownloaded = new File(destPath);
            FileUtils.copyURLToFile(new URL(url),fileToDownloaded);
            def status2 = client.target(url).request().delete().getStatus();
            log.info("DELETED ? ${status2}")
            if(!recordm.attach(instanceId,fieldName,fileToDownloaded.getName(),fileToDownloaded).success()){
                log.error("DOWNLOADED CONVERTED PDF FILE NOT ATTACHED")
            }
            return true
        }catch(Exception e){
            log.error("${e.getMessage()}")
            e.printStackTrace()
        }finally{
            fileToDownloaded.delete()
        }
    }else{
        log.error("ERROR CONVERTING THE FILE TO PDF. RESPONSE STATUS: ${status}")
    }
    return false
}
def markdownToHTML(inputTxt){
    MutableDataSet options = new MutableDataSet();
    options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()));
    options.set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX,"language-");
    options.set(Parser.BLOCK_QUOTE_EXTEND_TO_BLANK_LINE,true);
    Parser parser = Parser.builder(options).build();
    HtmlRenderer renderer = HtmlRenderer.builder(options).build();
    // You can re-use parser and renderer instances
    Node document = parser.parse(inputTxt);
    String html = renderer.render(document);
    return html;
}