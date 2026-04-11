package com.resumestudio.reviewer.nlp;

import jakarta.annotation.PostConstruct;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Part-of-Speech tagger using OpenNLP.
 * Used to distinguish verbs from nouns in skill extraction.
 */
@Component
public class PosTagService {

    private static final Logger log = LoggerFactory.getLogger(PosTagService.class);

    @Value("classpath:nlp/en-pos-maxent.bin")
    private Resource posModelResource;

    @Value("classpath:nlp/en-token.bin")
    private Resource tokenizerModelResource;

    private POSTaggerME posTagger;
    private TokenizerME tokenizer;

    @PostConstruct
    public void init() {
        try (InputStream posModelStream = posModelResource.getInputStream();
             InputStream tokenizerModelStream = tokenizerModelResource.getInputStream()) {
            
            POSModel posModel = new POSModel(posModelStream);
            posTagger = new POSTaggerME(posModel);
            
            TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelStream);
            tokenizer = new TokenizerME(tokenizerModel);
            
            log.info("POS tagger initialized");
        } catch (IOException e) {
            log.error("Failed to load POS tagger models", e);
            throw new RuntimeException("Failed to load POS tagger", e);
        }
    }

    /**
     * Tag a single word in context.
     * Returns POS tag (VB, NN, JJ, etc.)
     */
    public String tag(String word, String context) {
        if (word == null || word.isBlank()) {
            return "NN"; // Default to noun
        }
        
        // Tokenize context
        String[] tokens = tokenizer.tokenize(context);
        String[] tags = posTagger.tag(tokens);
        
        // Find the word in tokens
        String wordLower = word.toLowerCase();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].toLowerCase().equals(wordLower)) {
                return tags[i];
            }
        }
        
        // If not found in context, tag the word alone
        String[] singleToken = tokenizer.tokenize(word);
        String[] singleTag = posTagger.tag(singleToken);
        return singleTag.length > 0 ? singleTag[0] : "NN";
    }

    /**
     * Check if a word is used as a verb in the given context.
     */
    public boolean isVerb(String word, String context) {
        String tag = tag(word, context);
        return tag.startsWith("VB"); // VB, VBD, VBG, VBN, VBP, VBZ
    }

    /**
     * Check if a word is used as a noun in the given context.
     */
    public boolean isNoun(String word, String context) {
        String tag = tag(word, context);
        return tag.startsWith("NN"); // NN, NNS, NNP, NNPS
    }

    /**
     * Tag all words in a sentence.
     * Returns array of POS tags.
     */
    public String[] tagSentence(String sentence) {
        String[] tokens = tokenizer.tokenize(sentence);
        return posTagger.tag(tokens);
    }

    /**
     * Get tokens and their POS tags.
     */
    public TokenTag[] tokenizeAndTag(String text) {
        String[] tokens = tokenizer.tokenize(text);
        String[] tags = posTagger.tag(tokens);
        
        TokenTag[] result = new TokenTag[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            result[i] = new TokenTag(tokens[i], tags[i]);
        }
        return result;
    }

    public static class TokenTag {
        public final String token;
        public final String tag;

        public TokenTag(String token, String tag) {
            this.token = token;
            this.tag = tag;
        }
    }
}
