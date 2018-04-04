/**

	Author: Eby Jacob
	Modified the tidepooler speachletRequest stream class to make it work for the redesigned platform for automation of tasks using Apache Active Message Queue Broker.
	
    Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package JmeterAI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.speech.slu.Intent;
import com.amazon.speech.slu.Slot;
import com.amazon.speech.speechlet.IntentRequest;
import com.amazon.speech.speechlet.LaunchRequest;
import com.amazon.speech.speechlet.Session;
import com.amazon.speech.speechlet.SessionEndedRequest;
import com.amazon.speech.speechlet.SessionStartedRequest;
import com.amazon.speech.speechlet.Speechlet;
import com.amazon.speech.speechlet.SpeechletException;
import com.amazon.speech.speechlet.SpeechletResponse;
import com.amazon.speech.ui.OutputSpeech;
import com.amazon.speech.ui.PlainTextOutputSpeech;
import com.amazon.speech.ui.SsmlOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SimpleCard;


public class SmartSpeechlet implements Speechlet {
    private static final Logger log = LoggerFactory.getLogger(SmartSpeechlet.class);

    private static final String SLOT_DOMAIN = "Domain";
    private static final String SLOT_USERS = "Users";
    private static final String SLOT_ITERATION = "Iteration";
    private static final String SLOT_RAMPUP= "Rampup";

    private static final String SESSION_DOMAIN = "domain";
    private static final String SESSION_USERS = "users";
    private static final String SESSION_ITERATION = "iteration";
    private static final String SESSION_RAMPUP = "rampup";
    private static final String SESSION_USER_DISPLAY = "displayUser";
    private static final String SESSION_USER_REQUEST = "requestUserParam";

    private static final String SESSION_DOMAIN_DISPLAY = "displayDomain";
    private static final String SESSION_DOMAIN_REQUEST = "requestDomainParam";

    
    private static final String ENDPOINT = "tcp://ec2-52-213-61-56.eu-west-1.compute.amazonaws.com:61616";

    // NOAA station codes
    private static final String DOMIAN_CODE_COG = "www.cognizant.com/en-uk/";
    private static final String DOMIAN_CODE_GOG = "www.google.co.uk/";
    private static final String DOMIAN_CODE_APP = "www.apple.com/";

    private static final int MONTH_TWO_DIGIT_THRESHOLD = 10;
    private static final double ROUND_TO_HALF_THRESHOLD = 0.75;
    private static final double ROUND_DOWN_THRESHOLD = 0.25;

    private static final HashMap<String, String> DOMAINS = new HashMap<String, String>();

    static {
    	DOMAINS.put("cognizant", DOMIAN_CODE_COG);
    	DOMAINS.put("google", DOMIAN_CODE_GOG);
    	DOMAINS.put("apple", DOMIAN_CODE_APP);
    	}

    @Override
    public void onSessionStarted(final SessionStartedRequest request, final Session session)
            throws SpeechletException {
        log.info("onSessionStarted requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        // any initialization logic goes here
    }

    @Override
    public SpeechletResponse onLaunch(final LaunchRequest request, final Session session) throws SpeechletException 
    {
        		log.info("onLaunch requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());
        		return getWelcomeResponse();
    }

    @Override
    public SpeechletResponse onIntent(final IntentRequest request, final Session session) throws SpeechletException 
    {
        log.info("onIntent requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        Intent intent = request.getIntent();
        String intentName = intent.getName();

        if ("OneshotJMIntent".equals(intentName)) {
            return handleOneshotJMRequest(intent, session);
        } else if ("DialogJMIntent".equals(intentName)) {
            // Determine if this turn is for city, for date, or an error.
            // We could be passed slots with values, no slots, slots with no value.
            Slot domainSlot = intent.getSlot(SLOT_DOMAIN);
            Slot usersSlot = intent.getSlot(SLOT_USERS);
            //Slot iterationSlot = intent.getSlot(SLOT_ITERATION);
            //Slot rampupSlot = intent.getSlot(SLOT_RAMPUP);            
            if (domainSlot != null && domainSlot.getValue() != null) {//was city
                return handleDomainDialogRequest(intent, session); // domain
            } else if (usersSlot != null && usersSlot.getValue() != null) {//was date
                return handleUsersDialogRequest(intent, session);
            } 

            else {
                return handleNoSlotDialogRequest(intent, session);
            }
        } else if ("SupportedTasksIntent".equals(intentName)) {
            return handleSupportedTasksRequest(intent, session);
        } else if ("AMAZON.HelpIntent".equals(intentName)) {
            return handleHelpRequest();
        } else if ("AMAZON.StopIntent".equals(intentName)) {
            PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
            outputSpeech.setText("Goodbye");
            return SpeechletResponse.newTellResponse(outputSpeech);
        } else if ("AMAZON.CancelIntent".equals(intentName)) {
            PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
            outputSpeech.setText("Goodbye");
            return SpeechletResponse.newTellResponse(outputSpeech);
        } else {
            throw new SpeechletException("Invalid Intent");
        }
    }

    @Override
    public void onSessionEnded(final SessionEndedRequest request, final Session session)
            throws SpeechletException {
        log.info("onSessionEnded requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());
    }

    private SpeechletResponse getWelcomeResponse() {
        String whatTasks = "What task would you like to perform?";
        String speechOutput = "<speak>"
                + "Welcome to Coignizant cognitive platform of artifitial intelligence. "
                + "<audio src='https://s3.amazonaws.com/ask-storage/tidePooler/OceanWaves.mp3'/>"
                + whatTasks
                + "</speak>";
        String repromptText =
                "I have learned how to create a jmeter test. In Future I will support more on monitoring the test started,  and can provide information from Cognizant Smart APM platform "
                        + "You can simply open Cognizant smart A I  and ask a question like, "
                        + "Can you start a Jmeter test?"
                        + "For a list of supported tasks i have learned to perform, ask what tasks are supported. "
                        + whatTasks;

        return newAskResponse(speechOutput, true, repromptText, false);
    }

    private SpeechletResponse handleHelpRequest() {
        String repromptText = "What tasks would you like to perform?";
        String speechOutput =
                "Currently, I can lead you through starting a Jmeter test and "
                        + "or you can simply Smart A I and ask a question like, "
                        + "Can you start a Jmeter test"
                        + "For a list of supported cities, ask what tasks are supported. "
                        + "Or you can say exit. " + repromptText;

        return newAskResponse(speechOutput, repromptText);
    }

    /**
     * Handles the case where we need to know which city the user needs tide information for.
     */
    
    private SpeechletResponse handleSupportedDomainsRequest(final Intent intent,
            final Session session) {
        // get city re-prompt
        String repromptText = "Which domain would you like the test for?";
        String speechOutput =
                "Currently, I can run tests for these website domains: "
                        + getAllDomainsText() + repromptText;

        return newAskResponse(speechOutput, repromptText);
    }
    
    
    /**
     * Handles the case where we need to know which city the user needs tide information for.
     */
    
    private SpeechletResponse handleSupportedTasksRequest(final Intent intent,
            final Session session) {
    	String repromptText = "What tasks would you like to perform?";
        String speechOutput =
                "Currently, I can lead you through starting a Jmeter test and "
                        + "or you can simply Smart A I and ask a question like, "
                        + "Can you start a Jmeter test"
                        + "For a list of supported cities, ask what tasks are supported. "
                        + "Or you can say exit. " + repromptText;

        return newAskResponse(speechOutput, repromptText);
    }    

    /**
     * Handles the dialog step where the user provides a city.
     */
    private SpeechletResponse handleDomainDialogRequest(final Intent intent, final Session session) {
        DomainUserValues<String, String> Domain;
        try {
        	Domain = getDomainFromIntent(intent, false);
        } catch (Exception e) {
            String speechOutput =
                    "Currently, I can run tests for these website domains: "
                            + getAllDomainsText()
                            + "Which Domain would you like the test for?";          

            // repromptText is the speechOutput
            return newAskResponse(speechOutput, speechOutput);
        }

        /*
        SESSION_DOMAIN
        SESSION_USERS        
        */

        // if we don't have a date yet, go to date. If we have a date, we perform the final request

        if (session.getAttributes().containsKey(SESSION_USERS)) {
            String users = (String) session.getAttribute(SESSION_USERS);
            DomainUserValues<String, String> userObject =
                    new DomainUserValues<String, String>("users", users);
            return getFinalJMResponse(Domain, userObject);

        } else {
            // set domain in session and prompt for users
            session.setAttribute(SESSION_DOMAIN_DISPLAY, Domain.speechValue);
            session.setAttribute(SESSION_DOMAIN_REQUEST, Domain.apiValue);
            String speechOutput = "How many Users?";
            String repromptText =
                    "How many users would you like to run the test for " + Domain.speechValue
                            + "?";

            return newAskResponse(speechOutput, repromptText);

        }
    }

    /**
     * Handles the dialog step where the user provides a date.
     */

    private SpeechletResponse handleUsersDialogRequest(final Intent intent, final Session session) {
        DomainUserValues<String, String> usersObject = getUsersFromIntent(intent);
        // if we don't have a city yet, go to city. If we have a city, we perform the final request
        if (session.getAttributes().containsKey(SESSION_USERS)) {
            String users = (String) session.getAttribute(SESSION_USERS);
            DomainUserValues<String, String> usermap =
                    new DomainUserValues<String, String>("users", users);
            return getFinalJMResponse(usermap, usersObject);
        } else {
            // The user provided a date out of turn. Set date in session and prompt for city
            session.setAttribute(SESSION_USER_DISPLAY, usersObject.speechValue);
            session.setAttribute(SESSION_USER_REQUEST, usersObject.apiValue);
            String speechOutput =
                    "How many users should the test be configured for testing " + usersObject.speechValue
                            + "?";
            String repromptText = "How many users?";

            return newAskResponse(speechOutput, repromptText);
        }
    }

    /**
     * Handle no slots, or slot(s) with no values. In the case of a dialog based skill with multiple
     * slots, when passed a slot with no value, we cannot have confidence it is is the correct slot
     * type so we rely on session state to determine the next turn in the dialog, and reprompt.
     */

    private SpeechletResponse handleNoSlotDialogRequest(final Intent intent, final Session session) {
        if (session.getAttributes().containsKey(SESSION_DOMAIN)) {
            // get date re-prompt
            String speechOutput =
                    "Please try again saying start test for domain and number of users ";

            // repromptText is the speechOutput
            return newAskResponse(speechOutput, speechOutput);
        } else {
            // get city re-prompt
            return handleSupportedDomainsRequest(intent, session);
        }
    }

    /**
     * This handles the one-shot interaction, where the user utters a phrase like: 'Alexa, open Tide
     * Pooler and get tide information for Seattle on Saturday'. If there is an error in a slot,
     * this will guide the user to the dialog approach.
     */
    private SpeechletResponse handleOneshotJMRequest(final Intent intent, final Session session) {
        // Determine domain, using default if none provided
        DomainUserValues<String, String> domainObject = null;
        try {
        	domainObject = getDomainFromIntent(intent, true);
        } catch (Exception e) {
            // invalid city. move to the dialog
            String speechOutput =
                    "Currently, I can run tests for these website domains: "
                            + getAllDomainsText()
                            + "Which Domain would you like the test for?";   

            // repromptText is the same as the speechOutput
            return newAskResponse(speechOutput, speechOutput);
        }

        // Determine custom users
        DomainUserValues<String, String> usersObject = getUsersFromIntent(intent);

        // all slots filled, either from the user or by default values. Move to final request
        return getFinalJMResponse(domainObject, usersObject);
    }

    private SpeechletResponse getFinalJMResponse(DomainUserValues<String, String> domain,
            DomainUserValues<String, String> users) {
        return makeStartJmeterRequest(domain, users);
    }
    
    private SpeechletResponse makeStartJmeterRequest(DomainUserValues<String, String> domain,
            DomainUserValues<String, String> users) 
    {
    	String speechOutput = "";
        // Create the Simple card content.
        SimpleCard card = new SimpleCard();
        // Create the plain text output
        PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
        try {
		String Task="scenarioName=AI_JMeter_Test_For_Demo" + "&Domain=" + domain.apiValue +"&numLoops=10&numThreads=" 
		+ users.apiValue + "&RampUp=1&Command=Start Test";
        
        
        Producer pr = new Producer() ;

			pr.init();
			String responseJMTask = pr.sendMessage(Task);
			pr.destroy();
         
        if (responseJMTask.equalsIgnoreCase("Processed command Start Test"))
        	speechOutput="Jmeter Test started successfully for domain " + domain.apiValue + ". Once the test is complete the results will be available in csv and jtl formats";
        

        card.setTitle("Jmeter AI");
        card.setContent(speechOutput);


        outputSpeech.setText(speechOutput);
        } catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        return SpeechletResponse.newTellResponse(outputSpeech, card);
    }

 

    /**
     * Gets the city from the intent, or throws an error.
     */
    private DomainUserValues<String, String> getDomainFromIntent(final Intent intent,
            final boolean assignDefault) throws Exception {
        Slot domainSlot = intent.getSlot(SLOT_DOMAIN);
        DomainUserValues<String, String> domainObject = null;
        // slots can be missing, or slots can be provided but with empty value.
        // must test for both.
        if (domainSlot == null || domainSlot.getValue() == null) {
            if (!assignDefault) {
                throw new Exception("");
            } else {
                // For sample skill, default to Seattle.
                domainObject = new DomainUserValues<String, String>("cognizant", DOMAINS.get("cognizant"));
            }
        } else {
            // lookup the city. Sample skill uses well known mapping of a few known cities to
            // station id.
            String domainName = domainSlot.getValue();
            if (DOMAINS.containsKey(domainName.toLowerCase())) {
                domainObject =
                        new DomainUserValues<String, String>(domainName, DOMAINS.get(domainName.toLowerCase()));
            } else {
                throw new Exception(domainName);
            }
        }
        return domainObject;
    }


    
    /***
     * Gets the date from the intent, defaulting to today if none provided, or returns an error.
     **/
    private DomainUserValues<String, String> getUsersFromIntent(final Intent intent) {
        Slot usersSlot = intent.getSlot(SLOT_USERS);
        DomainUserValues<String, String> usersObject = new DomainUserValues<String, String>("users", "10");

        // slots can be missing, or slots can be provided but with empty value.
        // must test for both
        if (usersSlot == null || usersSlot.getValue() == null) 
            // default to today
            return usersObject;
        else 
        	
        	{
        	DomainUserValues<String, String> usersNewObject = new DomainUserValues<String, String>("users", usersSlot.getValue());
        	return usersNewObject;
        	}
        
    }

    private String getAllDomainsText() {
        StringBuilder dopmainList = new StringBuilder();
        for (String domain : DOMAINS.keySet()) {
        	dopmainList.append(domain);
        	dopmainList.append(", ");
        }
        return dopmainList.toString();
    }

    /**
     * Wrapper for creating the Ask response from the input strings with
     * plain text output and reprompt speeches.
     *
     * @param stringOutput
     *            the output to be spoken
     * @param repromptText
     *            the reprompt for if the user doesn't reply or is misunderstood.
     * @return SpeechletResponse the speechlet response
     */
    private SpeechletResponse newAskResponse(String stringOutput, String repromptText) {
        return newAskResponse(stringOutput, false, repromptText, false);
    }

    /**
     * Wrapper for creating the Ask response from the input strings.
     *
     * @param stringOutput
     *            the output to be spoken
     * @param isOutputSsml
     *            whether the output text is of type SSML
     * @param repromptText
     *            the reprompt for if the user doesn't reply or is misunderstood.
     * @param isRepromptSsml
     *            whether the reprompt text is of type SSML
     * @return SpeechletResponse the speechlet response
     */
    private SpeechletResponse newAskResponse(String stringOutput, boolean isOutputSsml,
            String repromptText, boolean isRepromptSsml) {
        OutputSpeech outputSpeech, repromptOutputSpeech;
        if (isOutputSsml) {
            outputSpeech = new SsmlOutputSpeech();
            ((SsmlOutputSpeech) outputSpeech).setSsml(stringOutput);
        } else {
            outputSpeech = new PlainTextOutputSpeech();
            ((PlainTextOutputSpeech) outputSpeech).setText(stringOutput);
        }

        if (isRepromptSsml) {
            repromptOutputSpeech = new SsmlOutputSpeech();
            ((SsmlOutputSpeech) repromptOutputSpeech).setSsml(stringOutput);
        } else {
            repromptOutputSpeech = new PlainTextOutputSpeech();
            ((PlainTextOutputSpeech) repromptOutputSpeech).setText(repromptText);
        }

        Reprompt reprompt = new Reprompt();
        reprompt.setOutputSpeech(repromptOutputSpeech);
        return SpeechletResponse.newAskResponse(outputSpeech, reprompt);
    }



    /**
     * Encapsulates the speech and api value for date and citystation objects.
     *
     * @param <L>
     *            text that will be spoken to the user
     * @param <R>
     *            text that will be passed in as an input to an API
     */
    private static class DomainUserValues<L, R> {
        private final L speechValue;
        private final R apiValue;

        public DomainUserValues(L speechValue, R apiValue) {
            this.speechValue = speechValue;
            this.apiValue = apiValue;
        }
    }
    
    

    private class Producer {
    	  private final Log log = LogFactory.getLog(getClass());
    	  
    	  private Connection connection;
    	  private javax.jms.Session session;
    	  private MessageProducer producer;
    	  private MessageConsumer consumer;
    	  
    	  private String response;

    	  public void init() throws Exception {
    	    // set 'er up
    	    ActiveMQConnectionFactory connectionFactory = 
    	    //  new ActiveMQConnectionFactory("tcp://localhost:61616");
    	    new ActiveMQConnectionFactory("tcp://ec2-52-213-61-56.eu-west-1.compute.amazonaws.com:61616");
    	    
    	    connection = connectionFactory.createConnection();
    	    session = connection.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
    	    // create our request and response queues
    	    Queue request = session.createQueue("request.queue");
    	    Queue response = session.createQueue("response.queue");
    	    // and attach a consumer and producer to them
    	    producer = session.createProducer(request);
    	    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    	    consumer = session.createConsumer(response);
    	    // and start your engines...
    	    connection.start();
    	  }

    	  public void destroy() throws Exception {
    	    session.close();
    	    connection.close();
    	    log.info("Closed client connection ");
    	  }
    	  
    	  public String sendMessage(String messageText) throws Exception {
    	    try {
    	      log.info("Client: Send request [" + messageText + "]");
    	      TextMessage message = session.createTextMessage(messageText);
    	      String messageId = UUID.randomUUID().toString();
    	      message.setJMSCorrelationID(messageId);
    	      producer.send(message);
    	      Message response = consumer.receive();
    	      String responseText = ((TextMessage) response).getText(); 
    	      return responseText;
    	    } catch (JMSException e) {
    	      log.error("JMS Exception on client", e);
    	    }
    	    return response;
    	  }
    	}    
    
}