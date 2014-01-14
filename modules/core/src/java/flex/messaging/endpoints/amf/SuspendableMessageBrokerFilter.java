/*************************************************************************
 *
 * ADOBE CONFIDENTIAL
 * __________________
 *
 *  Copyright 2008 Adobe Systems Incorporated
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 **************************************************************************/

package flex.messaging.endpoints.amf;

import flex.messaging.FlexContext;
import flex.messaging.FlexSession;
import flex.messaging.MessageException;
import flex.messaging.endpoints.AbstractEndpoint;
import flex.messaging.io.MessageIOConstants;
import flex.messaging.io.SerializationException;
import flex.messaging.io.amf.ActionContext;
import flex.messaging.io.amf.ActionMessage;
import flex.messaging.io.amf.MessageBody;
import flex.messaging.messages.CommandMessage;
import flex.messaging.messages.ErrorMessage;
import flex.messaging.messages.Message;
import flex.messaging.messages.MessagePerformanceUtils;
import flex.messaging.util.StringUtils;
import flex.messaging.log.LogCategories;
import flex.messaging.log.Log;
import flex.messaging.util.ExceptionUtil;
import flex.messaging.util.UUIDUtils;

import java.util.List;
import java.io.IOException;
import java.lang.reflect.Array;

/**
 * Filter that routes deserialized Flex messages to the <tt>MessageBroker</tt> for processing.
 * This implementation performs no internal synchronization but will suspend the chain if the 
 * response message is an <tt>AcknowledgeMessage</tt> with a {@link #SUSPEND_PROCESSING_HEADER}
 * header set. This sentinel message must be returned by a server component that has chosen to complete its processing
 * of the inbound message at a future point. When the component decides to resume processing it must assign the 
 * actual response message to return to the <code>outMessage</code> property of this filter and invoke 
 * <code>resume()</code> on the specific filter, or on the chain that the filter is a member of.
 */
public class SuspendableMessageBrokerFilter extends SuspendableAMFFilter
{
    //--------------------------------------------------------------------------
    //
    // Public Static Constants
    //
    //--------------------------------------------------------------------------    

    /**
     * The message header that must be set in <tt>AcknowledgeMessage</tt> responses that will trigger
     * a suspension of filter chain processing.
     */
    public static final String SUSPEND_PROCESSING_HEADER = "DSSuspendMessageBrokerFilterProcessing";

    //--------------------------------------------------------------------------
    //
    // Private Static Constants
    //
    //--------------------------------------------------------------------------    

    // Error codes.
    private static final int UNHANDLED_ERROR = 10000;
    private static final int UNHANDLED_SERIALIZATION_ERROR = 10306;
    
    // Default log category for this filter.
    private static final String LOG_CATEGORY = LogCategories.MESSAGE_GENERAL; 

    //--------------------------------------------------------------------------
    //
    // Constructor
    //
    //--------------------------------------------------------------------------    
    
    /**
     * Constructs a <tt>SuspendableMessageBrokerFilter</tt>.
     * The endpoint using this filter must pass a reference to itself in order for the filter
     * to dispatch messages back to the broker by way of {@link flex.messaging.endpoints.AbstractEndpoint#serviceMessage(Message)}.
     * The endpoint logging category is also passed in so that if any errors are generated by the filter
     * in its handling of the inbound and outbound messages separately from core server processing, these errors
     * will be logged out under the endpoint's log category. 
     * 
     * @param endpoint The endpoint using this filter.
     * @param endpointLogCategory The log category to use for logging any errors that occur before or after core
     *        message processing in the broker.
     */
    public SuspendableMessageBrokerFilter(AbstractEndpoint endpoint, String endpointLogCategory)
    {
        this.endpoint = endpoint;
        this.endpointLogCategory = endpointLogCategory;
    }

    //--------------------------------------------------------------------------
    //
    // Variables
    //
    //--------------------------------------------------------------------------

    /**
     * The associated endpoint; use this to service/process messages.
     */
    protected AbstractEndpoint endpoint; 
    
    /**
     * The associated endpoint's log category; used for any non-core messaging handling errors.
     */
    protected String endpointLogCategory;
    
    /**
     * The current inbound message being processed.
     */
    protected Message inMessage;
    
    /**
     * The AMF response to append the Flex 2 response message to.
     */
    protected MessageBody response;

    //--------------------------------------------------------------------------
    //
    // Properties
    //
    //--------------------------------------------------------------------------    

    //----------------------------------
    //  responseMessage
    //----------------------------------            

    protected Message responseMessage;

    /**
     * Sets the response message to return when this suspended filter resumes processing.
     * 
     * @throws IllegalStateException If invoked when the filter is not suspended.
     */
    public void setResponseMessage(Message value)
    {
        if (!isSuspended())
            throw new IllegalStateException();
        
        responseMessage = value;
    }    
    
    //--------------------------------------------------------------------------
    //
    // Protected Methods
    //
    //--------------------------------------------------------------------------    

    /**
     * @see flex.messaging.endpoints.amf.SuspendableAMFFilter#doInboundFilter(ActionContext)
     */
    protected void doInboundFilter(final ActionContext context) throws IOException
    {
        // Handle a resumption; if a response message has been assigned during a suspension return
        // immediately in order to advance directly to the outbound processing state.
        // If a response message was not assigned, re-run processing of the current inMessage.
        if (responseMessage != null)
            return;
        
        // Regular inbound processing.
        try
        {
            MessageBody request = context.getRequestMessageBody();
            response = context.getResponseMessageBody();
    
            Object data = request.getData();
            if (data instanceof List)
            {
                data = ((List)data).get(0);
            }
            else if (data.getClass().isArray())
            {
                data = Array.get(data, 0);
            }
    
            if (data instanceof Message)
            {
                inMessage = (Message)data;
            }
            else
            {
                inMessage = null;
                throw new MessageException("Request was not of type flex.messaging.messages.Message");
            }
    
            try
            {
                // Lookup or create the correct FlexClient.
                endpoint.setupFlexClient(inMessage);
    
                // Assign a clientId if necessary.
                // We don't need to assign clientIds to general poll requests.
                if (inMessage.getClientId() == null &&
                    (!(inMessage instanceof CommandMessage) || ((CommandMessage)inMessage).getOperation() != CommandMessage.POLL_OPERATION))
                {
                    Object clientId = UUIDUtils.createUUID();
                    inMessage.setClientId(clientId);
                }
                
                // Messages received via the AMF channel can be batched (by NetConnection on the client) and
                // we must not put the handler thread into a poll-wait state if a poll command message is followed by
                // or preceded by other messages in the batch; the request-response loop must complete without waiting.
                // If the poll command is the only message in the batch it's OK to wait.
                // If it isn't OK to wait, tag the poll message with a header that short-circuits any potential poll-wait.
                if (inMessage instanceof CommandMessage)
                {
                    CommandMessage command = (CommandMessage)inMessage;
                    if ((command.getOperation() == CommandMessage.POLL_OPERATION) && (context.getRequestMessage().getBodyCount() != 1))
                        command.setHeader(CommandMessage.SUPPRESS_POLL_WAIT_HEADER, Boolean.TRUE); 
                }            
                
                // If MPI is enabled update the MPI metrics on the object referred to by the context 
                // and the messages
                if (context.isMPIenabled())            
                    MessagePerformanceUtils.setupMPII(context, inMessage);
    
                // Service the message.
                responseMessage = endpoint.serviceMessage(inMessage);
    
                // Suspend the chain if we get back a 'poison-pill' message header.
                if (responseMessage.headerExists(SUSPEND_PROCESSING_HEADER))
                {                    
                    suspend();
                    responseMessage = null; // Don't hang on to this response.
                    return;
                }
            }
            catch (MessageException e)
            {
                context.setStatus(MessageIOConstants.STATUS_ERR);
                
                responseMessage = e.createErrorMessage();
                ((ErrorMessage)responseMessage).setCorrelationId(inMessage.getMessageId());
                ((ErrorMessage)responseMessage).setDestination(inMessage.getDestination());
                ((ErrorMessage)responseMessage).setClientId(inMessage.getClientId());
    
                e.logAtHingePoint(inMessage, (ErrorMessage)responseMessage, null /* Use default message intros */);    
            }
            catch (Throwable t)
            {
                // Handle any uncaught failures. The normal exception path on the server
                // is to throw MessageExceptions which are handled in the catch block above, 
                // so if that was skipped we have an overlooked or serious problem.
                context.setStatus(MessageIOConstants.STATUS_ERR);
                
                MessageException lme = new MessageException();
                lme.setMessage(UNHANDLED_ERROR, new Object[] {t.getMessage()});
                
                responseMessage = lme.createErrorMessage();
                ((ErrorMessage)responseMessage).setCorrelationId(inMessage.getMessageId());
                ((ErrorMessage)responseMessage).setDestination(inMessage.getDestination());
                ((ErrorMessage)responseMessage).setClientId(inMessage.getClientId());
                
                if (Log.isError())
                {
                    Log.getLogger(LOG_CATEGORY).error("Unhandled error when processing a message: " +
                            t.toString() + StringUtils.NEWLINE +
                            "  incomingMessage: " + inMessage + StringUtils.NEWLINE +
                            "  errorReply: " + responseMessage + StringUtils.NEWLINE +
                            ExceptionUtil.exceptionFollowedByRootCausesToString(t) + StringUtils.NEWLINE);
                }
            }      
        }
        catch (Throwable t)
        {
            unhandledError(context, t);
        }
    } 
    
    /**
     * @see flex.messaging.endpoints.amf.SuspendableAMFFilter#doOutboundFilter(ActionContext)
     */
    protected void doOutboundFilter(final ActionContext context) throws IOException
    {
        try
        {
            Message messageToReturn = responseMessage;
            responseMessage = null;
            
            // If MPI is enabled update the MPI metrics on the object referred to by the context 
            // and the messages.
            if (context.isRecordMessageSizes() || context.isRecordMessageTimes())
            {
                MessagePerformanceUtils.updateOutgoingMPI(context, inMessage, messageToReturn);
            }              
        
            // If our channel-endpoint combination supports small messages, and
            // if we know the current protocol version supports small messages,
            // try to replace the message...
            FlexSession session = FlexContext.getFlexSession();
            if (session != null && session.useSmallMessages()
                    && !context.isLegacy()
                    && context.getVersion() >= MessageIOConstants.AMF3
                    && messageToReturn instanceof Message)
            {
                messageToReturn = endpoint.convertToSmallMessage(messageToReturn);
            }            
            
            if (!(messageToReturn instanceof ErrorMessage))
                response.setReplyMethod(MessageIOConstants.RESULT_METHOD);
            else
                response.setReplyMethod(MessageIOConstants.STATUS_METHOD);
            
            response.setData(messageToReturn);          
        }
        catch (Throwable t)
        {
            unhandledError(context, t);
        }
    }
    
    //--------------------------------------------------------------------------
    //
    // Private Methods
    //
    //--------------------------------------------------------------------------    
    
    /**
     * Utility method that handles any non-core error (indicating a problem in the 
     * AMF processing layer of this filter) by generating a error message, and returning
     * it to the client in the AMF response.
     * The error is also logged out under the associated endpoint's logging category
     * (rather than the general message log category).
     */
    private void unhandledError(ActionContext context, Throwable t)
    {
        context.setStatus(MessageIOConstants.STATUS_ERR);
        
        ActionMessage responseMessage = new ActionMessage();
        context.setResponseMessage(responseMessage);

        MessageBody responseBody = new MessageBody();
        responseBody.setTargetURI(context.getRequestMessageBody().getResponseURI());

        context.getResponseMessage().addBody(responseBody);

        MessageException methodResult;

        if (t instanceof MessageException)
        {
            methodResult = (MessageException)t;
        }
        else
        {
            // An unhandled error occurred while processing client request(s).
            methodResult = new SerializationException();
            methodResult.setMessage(UNHANDLED_SERIALIZATION_ERROR);
            methodResult.setRootCause(t);
        }

        responseBody.setReplyMethod(MessageIOConstants.STATUS_METHOD);
        responseBody.setData(methodResult);        

        if (Log.isInfo())
            Log.getLogger(endpointLogCategory).info("Client request could not be processed.", t);
    }
}