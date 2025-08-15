package org.bvnk.slackbot.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.bvnk.slackbot.model.SlackEvent;
import org.bvnk.slackbot.service.SlackService;
import org.bvnk.slackbot.service.BedrockService;
import org.bvnk.slackbot.service.DynamoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class AsyncProcessorHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(AsyncProcessorHandler.class);
    private static final String THINKING_EMOJI = "hourglass_flowing_sand";
    
    private final ObjectMapper objectMapper;
    private final SlackService slackService;
    private final BedrockService bedrockService;
    private final DynamoService dynamoService;
    
    public AsyncProcessorHandler() {
        this.objectMapper = new ObjectMapper();
        this.slackService = new SlackService();
        this.bedrockService = new BedrockService();
        this.dynamoService = new DynamoService();
    }
    
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Processing async event");
        
        try {
            String action = (String) input.get("action");
            
            if ("process_mention".equals(action)) {
                Map<String, Object> eventMap = (Map<String, Object>) input.get("event");
                SlackEvent slackEvent = objectMapper.convertValue(eventMap, SlackEvent.class);
                
                processMention(slackEvent);
            }
            
            return "Success";
            
        } catch (Exception e) {
            logger.error("Error processing async event", e);
            return "Error: " + e.getMessage();
        }
    }
    
    private void processMention(SlackEvent slackEvent) {
        SlackEvent.Event event = slackEvent.getEvent();
        String channel = event.getChannel();
        String threadTs = event.getThreadTs();
        String messageTs = event.getTs();
        String eventId = slackEvent.getEventId();
        
        try {
            // Add thinking reaction
            slackService.addReaction(channel, messageTs, THINKING_EMOJI);
            
            // Get bot user ID to filter out bot's own messages
            String botUserId = slackService.getBotUserId();

            logger.info("Processing event {}", event);
            
            // Get thread messages
            List<Map<String, Object>> threadMessages = slackService.getThreadMessages(channel, threadTs);
            
            // Parse the user's question using the improved extraction method
            String userQuestion = slackService.extractQuestionFromMention(event.getText());
            
            // Check for special commands
            String response = handleSpecialCommands(userQuestion);
            
            if (response == null) {
                // Format thread context for Bedrock using the improved formatting
                // Pass the trigger message timestamp to exclude it from context
                String threadContext = slackService.formatThreadMessagesForAI(threadMessages, botUserId, messageTs);
                
                // Get AI response from Bedrock
                response = bedrockService.getResponse(threadContext, userQuestion);
            }
            
            // Post response to thread
            slackService.postMessage(channel, threadTs, response);
            
            // Remove thinking reaction
            slackService.removeReaction(channel, messageTs, THINKING_EMOJI);
            
            // Update event status
            dynamoService.updateEventStatus(eventId, "completed");
            
        } catch (Exception e) {
            logger.error("Error processing mention", e);
            
            // Try to post error message
            try {
                slackService.postMessage(channel, threadTs, 
                    "Sorry, I encountered an error processing your request. Please try again.");
                slackService.removeReaction(channel, messageTs, THINKING_EMOJI);
            } catch (Exception ex) {
                logger.error("Failed to post error message", ex);
            }
            
            dynamoService.updateEventStatus(eventId, "error");
        }
    }
    
    private String handleSpecialCommands(String text) {
        String lowerText = text.toLowerCase().trim();
        
        if (lowerText.equals("help") || lowerText.equals("?")) {
            return "*Available Commands:*\n" +
                   "• `help` - Show this message\n" +
                   "• `summarize` - Get a summary of this thread\n" +
                   "• `action-items` - Extract action items from the thread\n" +
                   "• `key-points` - List key discussion points\n\n" +
                   "Or ask me any question about this thread!";
        }
        
        // Return null for non-command messages to process with AI
        return null;
    }
    
}