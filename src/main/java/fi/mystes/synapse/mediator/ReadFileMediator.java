/**
 * Copyright 2016 Mystes Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fi.mystes.synapse.mediator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.namespace.QName;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.soap.SOAPBody;
import org.apache.commons.io.IOUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.util.xpath.SynapseXPath;
import org.jaxen.JaxenException;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

/**
 * Custom mediator to read XML file content into the current payload either
 * directly to SOAP body element or to defined target element.
 * 
 * <readFile (fileName="path" | property="propertyName") contentType="text/plain|xml"
 * [attachXpath="expression"]/>
 */
public class ReadFileMediator extends AbstractMediator {

    private String fileName;

    private String property;
    
    private String contentType;
    
    private SynapseXPath attachXpath = null;

    /**
     * Invokes the mediator passing the current message for mediation. Each
     * mediator performs its mediation action, and returns true if mediation
     * should continue, or false if further mediation should be aborted.
     *
     * @param context
     *            Current message context for mediation
     * @return true if further mediation should continue, otherwise false
     */
    @Override
    public boolean mediate(MessageContext context) {
        SynapseLog synLog = getLog(context);

        if (synLog.isTraceOrDebugEnabled()) {
        	synLog.traceOrDebug("Starting ReadFileMediator");
        }

        SOAPBody body = context.getEnvelope().getBody();
        
        try {
        
        	InputStream inputStream = initProcessableFile(context);
			
			if (contentType != null) {
				
				OMElement fileElement = null;
				try {
					if (contentType.equalsIgnoreCase("text/plain")) {
						fileElement = OMAbstractFactory.getOMFactory().createOMElement(
			            		new QName("http://ws.apache.org/commons/ns/payload", "text"));
						
						StringWriter writer = new StringWriter();
				        IOUtils.copy(inputStream, writer, "UTF-8");
				        String content = writer.toString();
		            	context.getEnvelope().getBody().getFirstElement().detach();
		            	fileElement.setText(content);
			                
					} else if (contentType.equalsIgnoreCase("xml")) {
	                    fileElement = new StAXOMBuilder(inputStream).getDocumentElement();
					}
					
				} catch (Exception e) {
	                synLog.error("Error while parsing file : " + fileName);
	                context.setProperty("READ_FILE_RESPONSE", 
	                		"Error while parsing file : " + fileName);
	            }
				appendFileContentIntoGivenElement(context, body, fileElement);
            
			} else {
				if (synLog.isTraceOrDebugEnabled()) {
					synLog.traceOrDebug("Content Type of file " + fileName + "unknown or not declared.");
				}
				context.setProperty("READ_FILE_RESPONSE", "Content Type of file " + fileName + 
						" unknown or not declared.");
	        }
        } catch (Exception e) {
			if (synLog.isTraceOrDebugEnabled()) {
				synLog.traceOrDebug("Error trying to read file " + fileName + ". " + e.getMessage());
			}
			context.setProperty("READ_FILE_RESPONSE", "Error trying to read file " + fileName + ". " + e.getMessage());
        }
        
        if (synLog.isTraceOrDebugEnabled()) {
        	synLog.traceOrDebug("Ending ReadFileMediator");
        }
        context.setProperty("READ_FILE_RESPONSE", "OK");
        return true;
    }

    /**
     * Appends XML file content into the given target element (attachXpath or body).
     * 
     * @param context Used to log errors
     * @param body SOAP body to append file element to if attachXpath not set
     * @param fileElement Element containing file content to be appended to body
     */
	private void appendFileContentIntoGivenElement(MessageContext context, SOAPBody body, OMElement fileElement) {
		SynapseLog synLog = getLog(context);
		if (fileElement != null) {
			if (synLog.isTraceOrDebugEnabled()) {
				synLog.traceOrDebug("Element containing file content created");
			}
		    if (attachXpath != null) {
		        try {
					if (synLog.isTraceOrDebugEnabled()) {
						synLog.traceOrDebug("Adding to target element");
					}
		            OMElement node = (OMElement) attachXpath.selectSingleNode(body);
		            node.addChild(fileElement);
		        } catch (JaxenException e) {
		            handleException("Error occurred while evaluating xpath", e, context);
		        }

		    } else {
				if (synLog.isTraceOrDebugEnabled()) {
					synLog.traceOrDebug("Adding to body");
				}
		        body.addChild(fileElement);
		    }
		} else {
			if (synLog.isTraceOrDebugEnabled()) {
				synLog.traceOrDebug("Element containing file content not created");
			}
		}
	}

	/**
	 * Helper method to initialize file to read content from. Will try to get file name
	 * first from fileName attribute. If it fails, it will try to read file name from property.
	 * 
	 * @param context Contains property which may hold XML file name.
	 * 
	 * @return Reference to file object
	 * @throws URISyntaxException 
	 * @throws SftpException 
	 * @throws JSchException 
	 * @throws FileNotFoundException 
	 * @throws IOException 
	 */
	private InputStream initProcessableFile(MessageContext context) 
			throws URISyntaxException, JSchException, SftpException, FileNotFoundException {
		InputStream fileToProcess = null;
        if (fileName != null) {
        	URI aURI = new URI(fileName);
        	
        	if (aURI.getScheme().equals("file"))
        		fileToProcess = new FileInputStream(new File(aURI));
        	
        	else if (aURI.getScheme().equals("ftp") || aURI.getScheme().equals("sftp"))
        		fileToProcess = processFtpSftpInput(aURI);
        	
        	else
        		throw new URISyntaxException(fileName, "Unknown protocol");

        } else if (property != null) {
        	fileToProcess = new FileInputStream(new File((String) context.getProperty(property)));
        }
		return fileToProcess;
	}
	
	private InputStream processFtpSftpInput(URI aURI) throws JSchException, SftpException {
		JSch jsch = new JSch();
		Session session = jsch.getSession(aURI.getUserInfo().substring(0, aURI.getUserInfo().indexOf(":")), 
				aURI.getHost(), aURI.getPort());
		session.setConfig("StrictHostKeyChecking", "no");
		session.setPassword(aURI.getUserInfo().substring(aURI.getUserInfo().indexOf(":")+1));
		session.connect();

		Channel channel = session.openChannel("sftp");
		channel.connect();
		ChannelSftp sftpChannel = (ChannelSftp) channel;
		
        return sftpChannel.get(aURI.getPath());
	}
    
    /**
     * Getter for attachXpath
     * 
     * @return
     */
    public SynapseXPath getAttachXpath() {
        return attachXpath;
    }

    /**
     * Setter for attachXpath
     * 
     * @param attachXpath
     */
    public void setAttachXpath(SynapseXPath attachXpath) {
        this.attachXpath = attachXpath;
    }

    /**
     * Getter for property.
     * 
     * @return
     */
    public String getProperty() {
        return property;
    }

    /**
     * Setter for property which contains file to be processed.
     * 
     * @param property
     */
    public void setProperty(String property) {
        this.property = property;
    }

    /**
     * Getter for file's name.
     * 
     * @return
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Setter for file's name.
     * 
     * @param fileName
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Getter for file's content type.
     * 
     * @return
     */
	public String getContentType() {
		return contentType;
	}

	/**
     * Setter for file's content type.
     * 
     * @param contentType
     */
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

}
