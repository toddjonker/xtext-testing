package org.eclipselabs.xtext.utils.unittesting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.Assert;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CharStream;
import org.antlr.runtime.Token;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.mwe.utils.StandaloneSetup;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.GrammarUtil;
import org.eclipse.xtext.IGrammarAccess;
import org.eclipse.xtext.ParserRule;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.antlr.ITokenDefProvider;
import org.eclipse.xtext.parser.antlr.Lexer;
import org.eclipse.xtext.parser.antlr.XtextTokenStream;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.SaveOptions;
import org.eclipse.xtext.resource.SaveOptions.Builder;
import org.eclipse.xtext.util.EmfFormatter;
import org.eclipse.xtext.util.Pair;
import org.eclipse.xtext.util.Tuples;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.Issue;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

/**
 * <p>Base class for testing Xtext-based DSLs including validation, serialization, formatting, e.g.</p>
 * 
 * <p>{@see XtextTest} offers integration testing of model files (load, validate, serialize, compare)
 * as well as very specific unit-style testing for terminals, keywords and parser rules.</p>
 *
 * @author Karsten Thoms
 * @author Lars Corneliussen
 * @author Markus Völter
 * @author Alexander Nittka
 * 
 */
public abstract class XtextTest {
    
	protected String resourceRoot;
	
	/* STATE for #testFile. TO BE initialized in #before */
	protected FluentIssueCollection issues;
	private Set<Issue> assertedIssues;
	private boolean compareSerializedModelToInputFile;
	private boolean formatOnSerialize;
    /* END STATE for #testFile */
	
    private static Logger LOGGER = Logger.getLogger(XtextTest.class);
    
    @Inject
    protected ResourceSet resourceSet;
    
    @Inject
    private IResourceServiceProvider.Registry serviceProviderRegistry;
    
    @Inject
    private IGrammarAccess grammar;
    
    @Inject
    private IParser parser;
    
    @Inject
    private Lexer lexer;
    
    @Inject
    private ITokenDefProvider tokenDefProvider;
    
    public XtextTest() {
        this ("classpath://");
        
    }

    public XtextTest(String resourceRoot) {
        this.resourceRoot = resourceRoot;
    }

    @BeforeClass
    public static void init_internal() {
        new StandaloneSetup().setPlatformUri("..");
    }
    
    @Before
    public void before() {
    	issues = null;
    	assertedIssues = new HashSet<Issue>();
    	compareSerializedModelToInputFile = true;
    	formatOnSerialize = true;
    }
    
    private void ensureIsBeforeTestFile(){
    	if (issues != null) {
    		fail("Method " + new Throwable().fillInStackTrace().getStackTrace()[1].getMethodName() + " must be run BEFORE 'testFile' is executed!");
    	}
    }
    
    private void ensureIsAfterTestFile(){
    	if (issues == null) {
    		fail("Method " + new Throwable().fillInStackTrace().getStackTrace()[1].getMethodName() + " must be run AFTER 'testFile' is executed!");
    	}
    }
    
    @After
    public void after() {
        if (issues != null) {
        	dumpUnassertedIssues();
        	Assert.assertTrue("found unasserted issues " + issues.except(assertedIssues).getSummary(), issues.except(assertedIssues).getIssues().size() == 0);
        }
    }
    
    protected FluentIssueCollection testFile(String fileToTest, String... referencedResources) {
    	
    	LOGGER.info("------------------------------------------");
		LOGGER.info("testing " + fileToTest + " in test method " +this.getClass().getSimpleName() + "." + new Throwable().fillInStackTrace().getStackTrace()[1].getMethodName());
		
        for (String referencedResource : referencedResources) {
            URI uri = URI.createURI(resourceRoot + "/" + referencedResource);
            loadModel(resourceSet, uri, getRootObjectType(uri));
        }
        
        Pair<String,FluentIssueCollection> result = loadAndSaveModule(resourceRoot, fileToTest);
        
        String serialized = result.getFirst();
        
        if (compareSerializedModelToInputFile) {
	        String expected = loadFileContents(resourceRoot, fileToTest);
	        // Remove trailing whitespace, see Bug#320074
	        assertEquals(expected.trim(), serialized);
        }
        
        return issues = result.getSecond();
    }
    
    protected void testParserRule(String ruleName, String textToParse) {
        testParserRule(ruleName, textToParse, false);
    }

    private List<SyntaxErrorMessage> testParserRule(String textToParse, String ruleName,
            boolean errorsExpected) {
    	
    	ParserRule parserRule = (ParserRule) GrammarUtil.findRuleForName(grammar.getGrammar(), ruleName);
        
    	if (parserRule == null){
    		fail("Could not find ParserRule " + ruleName);
    	}
    	
    	IParseResult result = parser.parse(parserRule, new StringReader(textToParse));
        
        ArrayList<SyntaxErrorMessage> errors = Lists.newArrayList();
        ArrayList<String> errMsg = Lists.newArrayList();
        
        for (INode err : result.getSyntaxErrors()) {
        	errors.add(err.getSyntaxErrorMessage());
        	errMsg.add(err.getSyntaxErrorMessage().getMessage());
        }
        
        if (!errorsExpected && !errors.isEmpty()) {
            fail("Parsing of text '" + textToParse + "' for rule '" + ruleName
                    + "' failed with errors: " + errMsg);
        }
        if (errorsExpected && errors.isEmpty()) {
            fail("Parsing of text '" + textToParse + "' for rule '" + ruleName
                    + "' was expected to have parse errors.");
        }

        return errors;
    }

    protected void testParserRuleErrors(String textToParse, String ruleName, 
            String... expectedErrorSubstrings) {
        List<SyntaxErrorMessage> errors = testParserRule(textToParse, ruleName, true);
        
        Set<String> matchingSubstrings = new HashSet<String>();
        Set<String> assertedErrors = new HashSet<String>();
        
        for(final SyntaxErrorMessage err : errors){
        	boolean hadMatch = false;
        	for(final String substring : expectedErrorSubstrings) {
        		boolean contains = err.getMessage().contains(substring);
            	if (contains) {
            		hadMatch = true;
            		matchingSubstrings.add(substring);
            	}
        	}
        	
        	if (hadMatch) {
        		assertedErrors.add(err.getMessage());
        	}
        }
    
        StringBuilder error = new StringBuilder();
        if (expectedErrorSubstrings.length != matchingSubstrings.size()) {
        	error.append("Unmatched assertions:");
        	for (String string : expectedErrorSubstrings) {
				if (!matchingSubstrings.contains(string)){
					error.append("\n  - any error containing '" + string + "'");
				}
			}
        	error.append("\n");
        }
        
        if (assertedErrors.size() != errors.size()) {
        	error.append("Unasserted Errors:");
        	for (SyntaxErrorMessage err : errors) {
				if (!assertedErrors.contains(err.getMessage())){
					error.append("\n  - " + err.getMessage());
				}
			}	
        }
        
        String failMessage = error.toString();
        if (!failMessage.equals("")) {
        	fail("\n\n" + failMessage + "\n\n");
        }
    }
    
     /**
     * return the list of tokens created by the lexer from the given input
     * */
    protected List<Token> getTokens(String input) {
      CharStream stream = new ANTLRStringStream(input);
      lexer.setCharStream(stream);
      XtextTokenStream tokenStream = new XtextTokenStream(lexer,
          tokenDefProvider);
      @SuppressWarnings("unchecked")
      List<Token> tokens = tokenStream.getTokens();
      return tokens;
    }

    /**
     * return the name of the terminal rule for a given token
     * */
    protected String getTokenType(Token token) {
      return tokenDefProvider.getTokenDefMap().get(token.getType());
    }

    /**
     * check whether an input is chopped into a list of expected token types
     * */
    protected void testTerminal(String input, String... expectedTerminals) {
      List<Token> tokens = getTokens(input);
      assertEquals(input, expectedTerminals.length, tokens.size());
      for (int i = 0; i < tokens.size(); i++) {
        Token token = tokens.get(i);
        String exp = expectedTerminals[i];
        if (!exp.startsWith("'")) {
        	exp = "RULE_" + exp;
        }
        assertEquals(input, exp, getTokenType(token));
      }
    }

    /**
     * check that an input is not tokenised using a particular terminal rule
     * */
    protected void testNotTerminal(String input, String unexpectedTerminal) {
      List<Token> tokens = getTokens(input);
      assertEquals(input, 1, tokens.size());
      Token token = tokens.get(0);
      
      assertNotSame(input, "RULE_" + unexpectedTerminal, getTokenType(token));
    }

     /**
     * check that input is treated as a keyword by the grammar
     * */
    protected void testKeyword(String input) {
      // the rule name for a keyword is usually
      // the keyword enclosed in single quotes
      String rule = new StringBuilder("'").append(input).append("'").toString();
      testTerminal(input, rule);
    }

    /**
     * check that input is not treated as a keyword by the grammar
     * */
    protected void testNoKeyword(String keyword) {
      List<Token> tokens = getTokens(keyword);
      assertEquals(keyword, 1, tokens.size());
      String type = getTokenType(tokens.get(0));
      assertFalse(keyword, type.charAt(0) == '\'');
    }
    

    protected String loadFileContents(String rootPath, String filename) {
        URI uri = URI.createURI(resourceRoot + "/" + filename);
        try {
            InputStream is = resourceSet.getURIConverter().createInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int i;
            while ((i = is.read()) >= 0) {
                bos.write(i);
            }
            is.close();
            bos.close();
            return bos.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected Pair<String, FluentIssueCollection> loadAndSaveModule(String rootPath, String filename) {
        URI uri = URI.createURI(resourceRoot + "/" + filename);
        EObject m = loadModel(resourceSet, uri, getRootObjectType(uri));

        Resource r = resourceSet.getResource(uri, false);
        IResourceServiceProvider provider = serviceProviderRegistry
                .getResourceServiceProvider(r.getURI());
        List<Issue> result = provider.getResourceValidator().validate(r,
                CheckMode.ALL, null);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
        	Builder builder = SaveOptions.newBuilder();
        	if (formatOnSerialize) {
        		builder.format();
        	}
			SaveOptions s = builder.getOptions();
        	
            m.eResource().save(bos, s.toOptionsMap());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
        return Tuples.create(bos.toString(), new FluentIssueCollection(r, result, new ArrayList<String>()));
    }

    /**
     * Returns the expected type of the root element of the given resource.
     */
    protected Class<? extends EObject> getRootObjectType(URI uri) {
    	return null;
    }

    public void setResourceRoot(String resourceRoot) {
        this.resourceRoot = resourceRoot;
    }

    @SuppressWarnings("unchecked")
    protected <T extends EObject> T loadModel(ResourceSet rs, URI uri, Class<T> clazz) {
        Resource resource = rs.createResource(uri);
        try {
            resource.load(null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
        if (!resource.getWarnings().isEmpty()) {
        	LOGGER.error("Resource " + uri.toString() + " has warnings:");
            for (Resource.Diagnostic issue : resource.getWarnings()) {
            	LOGGER.error(issue.getLine() + ": " + issue.getMessage());
            }
        }
        
        if (!resource.getErrors().isEmpty()) {
        	LOGGER.error("Resource " + uri.toString() + " has errors:");
            for (Resource.Diagnostic issue : resource.getErrors()) {
            	LOGGER.error("    " + issue.getLine() + ": " + issue.getMessage());
            }
            fail("Resource has " + resource.getErrors().size() + " errors.");
        }

        assertFalse("Resource has no content", resource.getContents().isEmpty());
        EObject o = resource.getContents().get(0);
        // assure that the root element is of the expected type
        if (clazz != null) {
        	assertTrue(clazz.isInstance(o));
        }
        EcoreUtil.resolveAll(resource);
        assertAllCrossReferencesResolvable(resource.getContents().get(0));
        return (T) o;
    }

    protected void assertAllCrossReferencesResolvable(EObject obj) {
        boolean allIsGood = true;
        TreeIterator<EObject> it = EcoreUtil2.eAll(obj);
        while (it.hasNext()) {
            EObject o = it.next();
            for (EObject cr : o.eCrossReferences())
                if (cr.eIsProxy()) {
                    allIsGood = false;
                    System.err.println("CrossReference from " + EmfFormatter.objPath(o) + " to "
                            + ((InternalEObject) cr).eProxyURI() + " not resolved.");
                }
        }
        if (!allIsGood) {
            fail("Unresolved cross references in " + EmfFormatter.objPath(obj));
        }
    }
    
    protected void resetAssertedIssues() {
		assertedIssues.clear();
	}
    
    /**
     * If called prior to #testFile, serialization will be performed, 
     * but the result is not expected to exactly match the input file.
     */
    protected void ignoreSerializationDifferences(){
    	ensureIsBeforeTestFile();
    	
    	compareSerializedModelToInputFile = false;
    }
    
    /**
     * Serialization will occur without formatting, hence the
     * input model must not comply to formatting rules in order
     * to succeed.
     */
    protected void ignoreFormattingDifferences(){
    	ensureIsBeforeTestFile();
    	
    	formatOnSerialize = false;
    }
    
    /**
     * If called after to #testFile, the test wont fail for unasserted warnings.
     */
    protected void ignoreUnassertedWarnings(){
    	ensureIsAfterTestFile();
    	
    	// just treat the warnings left as asserted
    	assertedIssues.addAll(issues.warningsOnly().except(assertedIssues).getIssues());
    }
	
	protected void assertConstraints( FluentIssueCollection coll, String msg ) {
		ensureIsAfterTestFile();
		
		assertedIssues.addAll(coll.getIssues());
		Assert.assertTrue("failed "+msg+coll.getMessageString(), coll.evaluate() );
	}
	
	protected void assertConstraints( FluentIssueCollection coll) {
		ensureIsAfterTestFile();
		
		assertedIssues.addAll(coll.getIssues());
		Assert.assertTrue("<no id> failed"+coll.getMessageString(), coll.evaluate() );
	}
	
	protected void assertConstraints( String constraintID, FluentIssueCollection coll) {
		ensureIsAfterTestFile();
		
		assertedIssues.addAll(coll.getIssues());
		Assert.assertTrue(constraintID+" failed"+coll.getMessageString(), coll.evaluate() );
	}
	
	public EObject getEObject( URI uri ) {
		EObject eObject = issues.getResource().getEObject(uri.fragment());
		if ( eObject.eIsProxy()) {
			eObject = EcoreUtil.resolve(eObject, issues.getResource());
		}
		return eObject;
	}	
	
	private void dumpUnassertedIssues() {
		if ( issues.except(assertedIssues).getIssues().size() > 0 ) {
			LOGGER.warn("---- Unasserted Issues ----");
			for (Issue issue: issues.except(assertedIssues)) {
				FluentIssueCollection.dumpIssue( issues.getResource(), issue );
			}
		}
	}
}