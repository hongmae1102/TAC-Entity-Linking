package tac.kbp.slotfilling.bin;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import tac.kbp.slotfilling.configuration.Definitions;
import tac.kbp.slotfilling.patterns.slots.ORGSlots;
import tac.kbp.slotfilling.patterns.slots.PERSlots;
import tac.kbp.slotfilling.queries.AnswerGoldenStandard;
import tac.kbp.slotfilling.queries.LoadQueries;
import tac.kbp.slotfilling.queries.SFQuery;
import tac.kbp.slotfilling.queries.attributes.Attribute;
import tac.kbp.slotfilling.queries.attributes.ORG_Attributes;
import tac.kbp.slotfilling.queries.attributes.PER_Attributes;
import tac.kbp.slotfilling.relations.DocumentRelations;
import tac.kbp.slotfilling.relations.ReverbRelation;
import tac.kbp.utils.SHA1;

import com.mysql.jdbc.PreparedStatement;

public class Main {
	
	public static void main(String[] args) throws Exception {
		
		// create Options object
		Options options = new Options();

		// add options		
		options.addOption("run", false, "complete run");
		
		// add argument options
		Option queriesTrain = OptionBuilder.withArgName("queriesTrain").hasArg().withDescription("XML file containing queries for trainning").create( "queriesTrain" );
		Option queriesTest = OptionBuilder.withArgName("queriesTest").hasArg().withDescription("XML file containing queries for testing").create( "queriesTest" );
		Option queriesTestAnswers = OptionBuilder.withArgName("queriesTestAnswers").hasArg().withDescription("test queries answers").create( "queriesTestAnswers" );
		Option queriesTrainAnswers = OptionBuilder.withArgName("queriesTrainAnswers").hasArg().withDescription("train queries answers").create( "queriesTrainAnswers" );
				
		options.addOption(queriesTrain);
		options.addOption(queriesTrainAnswers);
		options.addOption(queriesTest);
		options.addOption(queriesTestAnswers);
				
		CommandLineParser parser = new GnuParser();
		CommandLine line = parser.parse( options, args );
		
		if (args.length == 0) {
			// automatically generate the help statement
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp(" ", options );
			System.exit(0);
		}
		
		else {		
			if (line.hasOption("run"))
				run(line);	
		}
	}
	
	public static void parseQueries(Map<String, SFQuery> queries) throws Exception {
		
		Set<String> keys = queries.keySet();
		
		for (String k : keys) {
			
			SFQuery q = queries.get(k);
			
			/*
			System.out.println("q_id: " + q.query_id);
			System.out.println("name: " + q.name);	
			System.out.println("ignore:" + q.ignore);
			*/			
			q.getSupportDocument();
			
			// get nodeid from KB: wiki_text, other attributes
			if (!q.nodeid.startsWith("NIL"))
				q.getKBEntry();
			
			// get sentences where entity occurs
			q.extractSentences();
			//System.out.println("sentences: " + q.sentences.size());
			
			/*
			// extract other entities in the support document
			q.getNamedEntities();			
			System.out.println("persons: " + q.persons.size());
			System.out.println("places: " + q.places.size());
			System.out.println("org: " + q.organizations.size());
			*/
					
			// get alternative senses for entity name and extract acronyms from support doc.
			q.getAlternativeSenses();
			//q.extracAcronyms();
			//System.out.println("senses: " + q.alternative_names);			
			//System.out.println("abbreviations: " + q.abbreviations);
			
			q.queryCollection();
			
		}
		
	}
	
	public static void recall(Map<String, SFQuery> queries) {
				
		Set<SFQuery> queries_with_zero_docs = new HashSet<SFQuery>();		
		Set<String> keys = queries.keySet();
		int answer_doc_founded = 0;
		int answer_doc_not_founded = 0;
		int total_answer_doc_founded = 0;
		int total_answer_doc_not_founded = 0;
		
		for (String k : keys) {
			
			SFQuery q = queries.get(k);			
			answer_doc_founded = 0;
			answer_doc_not_founded = 0;
			
			if (q.documents.size()==0) {
				queries_with_zero_docs.add(q);
				for (HashMap<String, String> answer : q.correct_answers) {
					answer_doc_not_founded++;
					total_answer_doc_not_founded++;
					q.answer_doc_not_founded.add(answer.get("slot_name"));
				}
				float coverage = ((float) answer_doc_founded / (float) (answer_doc_founded + answer_doc_not_founded));
				q.coverage = coverage;				
				continue;
			}
			
			else {
				for (HashMap<String, String> answer : q.correct_answers) {
					
					boolean found = false;
					
					for (Document d : q.documents) {					
						if (d.get("docid").equalsIgnoreCase(answer.get("docid"))) {
							found = true;
							answer_doc_founded++;
							total_answer_doc_founded++;
							q.answer_doc_founded.add(answer.get("slot_name"));
							break;
						}
					}
					
					if (!found) {
						answer_doc_not_founded++;
						total_answer_doc_not_founded++;
						q.answer_doc_not_founded.add(answer.get("slot_name"));
					}					
				}
				float coverage = ((float) answer_doc_founded / (float) (answer_doc_founded + answer_doc_not_founded));
				q.coverage = coverage;			
			}		
		}
		
		System.out.println("\nQueries with 0 docs retrieved: " + queries_with_zero_docs.size());
		System.out.println("average coverage: " + ( (float) total_answer_doc_founded / (float) (total_answer_doc_founded + total_answer_doc_not_founded)) );		
	}
		
	public static void loadQueriesAnswers(String filename, Map<String, SFQuery> queries) throws IOException {
		
		System.out.println("Loading answers from: " + filename);			
		
		try {
			
			FileInputStream fstream = new FileInputStream(filename);
			DataInputStream in = new DataInputStream(fstream);
			BufferedReader br = new BufferedReader(new InputStreamReader(in));			
			
			//first line is just the identifiers
			String strLine = br.readLine();
			strLine = br.readLine();
			
			String[] data = strLine.split("\t");
			String current_qid = data[1];

			SFQuery q = queries.get(current_qid);
			HashMap<String,String> answers = new HashMap<String, String>();			
			answers.put("slot_name", data[3]);
			answers.put("docid", data[4]);
			answers.put("start_char", data[5]);
			answers.put("end_char", data[6]);
			answers.put("response", data[7]);
			answers.put("norm_response", data[8]);
			answers.put("equiv_class_id", data[9]);
			answers.put("judgment", data[10]);
			q.correct_answers.add(answers);
			
			
			AnswerGoldenStandard answer = new AnswerGoldenStandard();			
			answer.slot_name = data[3];
			answer.docid = data[4];
			answer.start_char = data[5];
			answer.end_char = data[6];
			answer.response = data[7];
			answer.norm_response = data[8];
			answer.equiv_class_id = data[9];
			answer.judgment = data[10];			
			q.golden_answers.put(data[3], answer);
			
			
			
			while ((strLine = br.readLine()) != null)   {
				String previous_qid = current_qid;
				data = strLine.split("\t");
				current_qid = data[1];
				
				if (!current_qid.equalsIgnoreCase(previous_qid)) {					
					q = queries.get(current_qid);
					answers = new HashMap<String, String>();
					answers.put("slot_name", data[3]);
					answers.put("docid", data[4]);
					answers.put("start_char", data[5]);
					answers.put("end_char", data[6]);
					answers.put("response", data[7]);
					answers.put("norm_response", data[8]);
					answers.put("equiv_class_id", data[9]);
					answers.put("judgment", data[10]);
					q.correct_answers.add(answers);
					
					answer = new AnswerGoldenStandard();			
					answer.slot_name = data[3];
					answer.docid = data[4];
					answer.start_char = data[5];
					answer.end_char = data[6];
					answer.response = data[7];
					answer.norm_response = data[8];
					answer.equiv_class_id = data[9];
					answer.judgment = data[10];			
					q.golden_answers.put(data[3], answer);
					
				}
				
				else {
					answers = new HashMap<String, String>();
					answers.put("slot_name", data[3]);
					answers.put("docid", data[4]);
					answers.put("start_char", data[5]);
					answers.put("end_char", data[6]);
					answers.put("response", data[7]);
					answers.put("norm_response", data[8]);
					answers.put("equiv_class_id", data[9]);
					answers.put("judgment", data[10]);
					q.correct_answers.add(answers);
					
					answer = new AnswerGoldenStandard();
					answer.slot_name = data[3];
					answer.docid = data[4];
					answer.start_char = data[5];
					answer.end_char = data[6];
					answer.response = data[7];
					answer.norm_response = data[8];
					answer.equiv_class_id = data[9];
					answer.judgment = data[10];			
					q.golden_answers.put(data[3], answer);
				}
			}
			in.close();
		}
		
		catch (Exception e)	{
				//Catch exception if any			
				System.err.println("Error: " + e.getMessage());
		}
		
	}
	
	public static String getAnswerDocument(String docid) throws IOException {
        Term t = new Term("docid", docid); 
        Query query = new TermQuery(t);                 
        TopDocs docs = Definitions.documents.search(query, 1);
        ScoreDoc[] scoredocs = docs.scoreDocs;
        Document doc = Definitions.documents.doc(scoredocs[0].doc);        
        return doc.get("text");
	}
	
	public static void run(CommandLine line) throws Exception {
		
		Definitions.loadDocumentCollecion();
		//Definitions.loadClassifier("/collections/TAC-2011/resources/all.3class.distsim.crf.ser.gz");
		Definitions.connectionREDIS();
		Definitions.loadKBIndex();
		Definitions.getDBConnection();
		
		/* Load Train Queries + answers */
		String queriesTrainFile = line.getOptionValue("queriesTrain");
		System.out.println("\nLoading train queries from: " + queriesTrainFile);
		Map<String, SFQuery> train_queries = LoadQueries.loadXML(queriesTrainFile);		
		String queriesTrainAnswers = line.getOptionValue("queriesTrainAnswers");
		loadQueriesAnswers(queriesTrainAnswers,train_queries);
		
		System.out.println("Loaded: " + train_queries.size() + " train queries");
		
		/* Load Test Queries + answers */
		String queriesTestFile = line.getOptionValue("queriesTest");
		System.out.println("\nLoading test queries from: " + queriesTestFile);
		Map<String, SFQuery> test_queries = LoadQueries.loadXML(queriesTestFile);		
		String queriesTestAnswers = line.getOptionValue("queriesTestAnswers");
		loadQueriesAnswers(queriesTestAnswers,test_queries);
		
		/*
		 * Load patterns to extract slot answers
		 */
		
		PERSlots.load_patterns();
		ORGSlots.load_patterns();
		
		System.out.println("Loaded: " + test_queries.size() + " test queries");
		
		/* For the Train Queries: 
		 * 		get document answer for each attribute;
		 * 		get sentence with answer;
		 * 		get answer/normalized answer
		 */
		
		Set<String> train_queries_keys = train_queries.keySet();
		
		for (String q_id : train_queries_keys) {
			
			SFQuery q = train_queries.get(q_id);			
			//System.out.println(q.name + '\t' + q.query_id);	
			
			for (HashMap<String, String> a : q.correct_answers) {
				
				String slot_name = a.get("slot_name");
				String response = a.get("response");
				String norm_response = a.get("norm_response");
				String doc_id = a.get("docid");
				String start_char = a.get("start_char");
				String end_char = a.get("end_char");
				String judgment = a.get("judgment");
				
				//System.out.println(slot_name + '\t' + response + '\t' + doc_id);
					
				if (q.etype.equalsIgnoreCase("PER")) {		
					((PER_Attributes) q.attributes).attributes.get(slot_name).response.add(response);
					((PER_Attributes) q.attributes).attributes.get(slot_name).response_normalized.add(norm_response);
					((PER_Attributes) q.attributes).attributes.get(slot_name).slot_name = (slot_name);					
					((PER_Attributes) q.attributes).attributes.get(slot_name).answer_doc = getAnswerDocument(doc_id);
					((PER_Attributes) q.attributes).attributes.get(slot_name).start_char = Integer.parseInt(start_char);
					((PER_Attributes) q.attributes).attributes.get(slot_name).end_char = Integer.parseInt(end_char);
					((PER_Attributes) q.attributes).attributes.get(slot_name).judgment = Integer.parseInt(judgment);
				}
				
				else if (q.etype.equalsIgnoreCase("ORG")) {
					((ORG_Attributes) q.attributes).attributes.get(slot_name).response.add(response);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).response_normalized.add(norm_response);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).slot_name = (slot_name);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).answer_doc = getAnswerDocument(doc_id);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).start_char = Integer.parseInt(start_char);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).end_char = Integer.parseInt(end_char);
					((ORG_Attributes) q.attributes).attributes.get(slot_name).judgment = Integer.parseInt(judgment);
				}
			}
		}
		

		//check that insertions were done correctly
		for (String s : train_queries_keys) {			
			
			SFQuery q = train_queries.get(s);
				
			if (q.etype.equalsIgnoreCase("PER")) {
				
				HashMap<String,Attribute> a = ((PER_Attributes) q.attributes).attributes;
				Set<String> keys = a.keySet();
				
				/*
				System.out.println(q.name + '\t' + q.query_id);
				System.out.println("attributes: " + keys.size());
				*/				
				
				for (String k : keys) {
					
					if (a.get(k).answer_doc!=null && a.get(k).judgment==1) {
						//System.out.println(k);
						//System.out.println('\t' + a.get(k).answer.get(0));
						a.get(k).extractSentences();
						//System.out.println('\t' + a.get(k).answer_doc);						
						//System.out.println('\t' + a.get(k).answer.size());
					}
				}
			}
			
			else if (q.etype.equalsIgnoreCase("ORG")) {
				HashMap<String,Attribute> a = ((ORG_Attributes) q.attributes).attributes;
				Set<String> keys = a.keySet();
				
				/*
				System.out.println(q.name + '\t' + q.query_id);
				System.out.println("attributes: " + keys.size());
				*/
				for (String k : keys) {
					if (a.get(k).answer_doc!=null && a.get(k).judgment==1) {
						//System.out.println(k);
						//System.out.println('\t' + a.get(k).answer.get(0));				
						a.get(k).extractSentences();
						//System.out.println('\t' + a.get(k).answer_doc);						
						//System.out.println('\t' + a.get(k).answer.size());
					}
				}
			}
		}
		
		parseQueries(train_queries);		
		System.out.println("\n\n2010 queries");
		recall(train_queries);
		selectExtractions(train_queries);		
		evaluation(train_queries);
		outputresults(train_queries);
		Definitions.closeDBConnection();
	}
	
	public static DocumentRelations getExtractions(String docid, SFQuery q) throws Exception {
		
		String filename_sha1 = SHA1.digest(docid);
		
		PreparedStatement stm = (PreparedStatement) Definitions.connection.prepareStatement(
				
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction1 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction2 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction3 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction4 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction5 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction6 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction7 WHERE file_name_sha1 = ? UNION " +
			"SELECT arg1, rel, arg2, confidence, sentence FROM extraction8 WHERE file_name_sha1 = ? " +
			"ORDER BY confidence DESC"			
			);
		
		stm.setString(1, filename_sha1);
		stm.setString(2, filename_sha1);
		stm.setString(3, filename_sha1);
		stm.setString(4, filename_sha1);
		stm.setString(5, filename_sha1);
		stm.setString(6, filename_sha1);
		stm.setString(7, filename_sha1);
		stm.setString(8, filename_sha1);
		
		ResultSet resultSet = stm.executeQuery();
		LinkedList<ReverbRelation> relations = new LinkedList<ReverbRelation>();
		
		while (resultSet.next()) {
			String arg1 = resultSet.getString(1);			
			String rel = resultSet.getString(2);
			String arg2 = resultSet.getString(3);
			Float confidence = resultSet.getFloat(4);
			String sentence = resultSet.getString(5);			
			ReverbRelation relation = new ReverbRelation(docid, arg1, rel, arg2, sentence, confidence);		
			relations.add(relation);
		}
		
		DocumentRelations doc = new DocumentRelations(relations, docid);
		
		return doc;
	}
	
	public static void evaluation(Map<String, SFQuery> queries) {
		
		int correct_slots = 0;
		//int wrong_slots = 0;
		
		Set<String> keys = queries.keySet();
		
		for (String keyQ : keys) {			
			SFQuery q = queries.get(keyQ);
			
			/*
			System.out.println(q.query_id + '\t' + q.name + '\t' + q.coverage + '\t' + q.documents.size());
			System.out.println("q.answer_doc_founded: " + q.answer_doc_founded);
			System.out.println("q.answer_doc_not_founded: " + q.answer_doc_not_founded);
			System.out.println();
			*/
			
			for (HashMap<String, String> system_answer : q.system_answers) {
				
				for (HashMap<String, String> correct_answer : q.correct_answers) {
					if (correct_answer.get("slot_name").equalsIgnoreCase(system_answer.get("slot_name"))) {
						System.out.println("slot name: " + system_answer.get("slot_name"));
						System.out.println("correct answer: " + correct_answer.get("response") + '\t' + correct_answer.get("norm_response"));
						
						try {							
							System.out.println("system answer: " + system_answer.get("filler"));
							if ( system_answer.get("filler").equalsIgnoreCase(correct_answer.get("response")) || system_answer.get("filler").equalsIgnoreCase(correct_answer.get("norm_response"))) {							
								correct_slots++;							
								}
						} catch (Exception e) {
							// answer for this slot is NIL, no answer given							
						}
						/*
						system_answer.put("slot_name", slot.get("slot_name"));
						system_answer.put("slot_filler",relation.arg2);
						system_answer.put("justify", relation.docid);											
						system_answer.put("start_offset_filler", "0");
						system_answer.put("end_offset_filler", "1");
						system_answer.put("start_offset_justification", "0");
						system_answer.put("end_offset_justification", "1");
						system_answer.put("confidence_score", Float.toString(relation.confidence));
						answers.put("response", data[7]);
						answers.put("norm_response", data[8]);
						*/				
					}					
				}
			}
		}
	}
	
	public static void selectExtractions(Map<String, SFQuery> queries) throws Exception {
		
		Set<String> keys = queries.keySet();		
		
		for (String keyQ : keys) {			
			SFQuery q = queries.get(keyQ);
			
			System.out.println("query: " + q.name);
			System.out.println("support document: " + q.docid);
			System.out.println();
			
			/* Retrieve all relations extracted by ReVerb for each document retrieved and
			/* extract relations based on the slots that have to be filled, using the patterns mappings */
			
			for (Document d : q.documents) {
				DocumentRelations relations = getExtractions(d.get("docid"),q);
				
				if (q.etype.equalsIgnoreCase("PER")) {
				
				for (ReverbRelation relation : relations.relations) {
					
					for (HashMap<String, String> slot : q.correct_answers) {
						String slot_name = slot.get("slot_name");
						
						if (slot_name.equalsIgnoreCase("per:countries_of_residence") || slot_name.equalsIgnoreCase("per:stateorprovinces_of_residence") || slot_name.equalsIgnoreCase("per:cities_of_residence")) {
							LinkedList<String> patterns = PERSlots.slots_patterns.get("per:place_of_residence");
							//System.out.println("answer to slot: place_of_residence");						
							//System.out.println(PERSlots.slots_patterns.get(slot.get("per:place_of_residence")));
							for (String pattern : patterns) {
								/*
								System.out.println(q.name);
								System.out.println("MATCH!");
								System.out.println("slot to be filled: " + slot.get("slot_name"));
								System.out.println("docid: " + relation.docid);
								System.out.println("arg1: " + relation.arg1);
								System.out.println("rel: " + relation.rel);
								System.out.println("arg2: " + relation.arg2);
								System.out.println("sentence: " + relation.sentence +'\n');
								*/
								for (HashMap<String, String> answer : q.correct_answers) {
									if (answer.get("slot_name").equalsIgnoreCase(slot.get("slot_name"))) {
										/*
										System.out.print("correct response: " + answer.get("response"));
										System.out.print("\tdocid: " + answer.get("docid") + '\n');
										*/
										HashMap<String, String> system_answer = new HashMap<String, String>();										
										system_answer.put("slot_name", slot.get("slot_name"));
										system_answer.put("slot_filler",relation.arg2);
										system_answer.put("justify", relation.docid);											
										system_answer.put("start_offset_filler", "0");
										system_answer.put("end_offset_filler", "1");
										system_answer.put("start_offset_justification", "0");
										system_answer.put("end_offset_justification", "1");
										system_answer.put("confidence_score", Float.toString(relation.confidence));
										q.system_answers.add(system_answer);
									}
								}						
							}
						}
						
						else if (slot_name.equalsIgnoreCase("per:city_of_death") || slot_name.equalsIgnoreCase("per:stateorprovince_of_death") || slot_name.equalsIgnoreCase("per:country_of_death")) {
							LinkedList<String> patterns = PERSlots.slots_patterns.get("per:place_of_death");
							//System.out.println("answer to slot: place_of_death");
							//System.out.println("rel: " + rel);
							//System.out.println(PERSlots.slots_patterns.get(slot.get("per:place_of_death")));
							for (String pattern : patterns) {							
								if (pattern.equalsIgnoreCase(relation.rel)) {
									/*
									System.out.println(q.name);
									System.out.println("MATCH!");
									System.out.println("slot to be filled: " + slot.get("slot_name"));
									System.out.println("docid: " + relation.docid);
									System.out.println("arg1: " + relation.arg1);
									System.out.println("rel: " + relation.rel);
									System.out.println("arg2: " + relation.arg2);
									System.out.println("sentence: " + relation.sentence +'\n');
									*/
									for (HashMap<String, String> answer : q.correct_answers) {
										if (answer.get("slot_name").equalsIgnoreCase(slot.get("slot_name"))) {
											/*
											System.out.print("correct response: " + answer.get("response"));
											System.out.print("\tdocid: " + answer.get("docid") + '\n');
											*/
											HashMap<String, String> system_answer = new HashMap<String, String>();										
											system_answer.put("slot_name", slot.get("slot_name"));
											system_answer.put("slot_filler",relation.arg2);
											system_answer.put("justify", relation.docid);											
											system_answer.put("start_offset_filler", "0");
											system_answer.put("end_offset_filler", "1");
											system_answer.put("start_offset_justification", "0");
											system_answer.put("end_offset_justification", "1");
											system_answer.put("confidence_score", Float.toString(relation.confidence));
											q.system_answers.add(system_answer);
										}
									}
								}
							}			
						}
						
						else if (slot_name.equalsIgnoreCase("per:country_of_birth") || slot_name.equalsIgnoreCase("per:stateorprovince_of_birth") || slot_name.equalsIgnoreCase("per:city_of_birth")) {
							LinkedList<String> patterns = PERSlots.slots_patterns.get("per:place_of_birth");
							//System.out.println("answer to slot: country_of_birth");
							//System.out.println("rel: " + rel);
							//System.out.println(PERSlots.slots_patterns.get(slot.get("per:place_of_birth")));
							for (String pattern : patterns) {							
								/*
								System.out.println(q.name);
								System.out.println("MATCH!");
								System.out.println("slot to be filled: " + slot.get("slot_name"));
								System.out.println("docid: " + relation.docid);
								System.out.println("arg1: " + relation.arg1);
								System.out.println("rel: " + relation.rel);
								System.out.println("arg2: " + relation.arg2);
								System.out.println("sentence: " + relation.sentence +'\n');
								*/
								for (HashMap<String, String> answer : q.correct_answers) {
									if (answer.get("slot_name").equalsIgnoreCase(slot.get("slot_name"))) {
										/*
										System.out.print("correct response: " + answer.get("response"));
										System.out.print("\tdocid: " + answer.get("docid") + '\n');
										*/
										HashMap<String, String> system_answer = new HashMap<String, String>();										
										system_answer.put("slot_name", slot.get("slot_name"));
										system_answer.put("slot_filler",relation.arg2);
										system_answer.put("justify", relation.docid);											
										system_answer.put("start_offset_filler", "0");
										system_answer.put("end_offset_filler", "1");
										system_answer.put("start_offset_justification", "0");
										system_answer.put("end_offset_justification", "1");
										system_answer.put("confidence_score", Float.toString(relation.confidence));
										q.system_answers.add(system_answer);
									}
								}						
							}
						}
						
						else {
							LinkedList<String> patterns = PERSlots.slots_patterns.get(slot.get("slot_name"));
							//System.out.println("answer to slot: " + slot.get("slot_name"));
							//System.out.println("rel: " + rel);
							//System.out.println(PERSlots.slots_patterns.get(slot.get("slot_name")));						
							for (String pattern : patterns) {													
								if (pattern.equalsIgnoreCase(relation.rel)) {
									/*
									System.out.println(q.name);
									System.out.println("MATCH!");
									System.out.println("slot to be filled: " + slot.get("slot_name"));
									System.out.println("docid: " + relation.docid);
									System.out.println("arg1: " + relation.arg1);
									System.out.println("rel: " + relation.rel);
									System.out.println("arg2: " + relation.arg2);
									System.out.println("sentence: " + relation.sentence +'\n');
									*/
									for (HashMap<String, String> answer : q.correct_answers) {
										if (answer.get("slot_name").equalsIgnoreCase(slot.get("slot_name"))) {
											/*
											System.out.print("correct response: " + answer.get("response"));
											System.out.print("\tdocid: " + answer.get("docid") + '\n');
											*/
											
											HashMap<String, String> system_answer = new HashMap<String, String>();
											
											system_answer.put("slot_name", slot.get("slot_name"));
											system_answer.put("slot_filler",relation.arg2);
											system_answer.put("justify", relation.docid);											
											system_answer.put("start_offset_filler", "0");
											system_answer.put("end_offset_filler", "1");
											system_answer.put("start_offset_justification", "0");
											system_answer.put("end_offset_justification", "1");
											system_answer.put("confidence_score", Float.toString(relation.confidence));
											q.system_answers.add(system_answer);
										}
									}								
								}
							}
						}
					}
				}
			}
			/*	
			else if (q.etype.equalsIgnoreCase("ORG")) 
			{				}
			*/			
			}
		}
	}
		
	public static void outputresults(Map<String, SFQuery> queries) throws IOException {
		
		int unique_run_id = 0;		
		FileWriter output = new FileWriter("results.txt"); 
		Set<String> keys = queries.keySet();
		
		for (String keyQ : keys) {			
			SFQuery q = queries.get(keyQ);
			
			for (HashMap<String, String> answer : q.system_answers) {
				
				output.write(q.query_id + '\t' + answer.get("slot_name") + '\t' + Integer.toString(unique_run_id) + '\t' + answer.get("justify") + 
						'\t' + answer.get("slot_filler") + '\t' + answer.get("start_offset_filler") + '\t' + answer.get("end_offset_filler") + '\t'
						+ answer.get("start_offset_justification") + '\t' + answer.get("end_offset_justification") + '\t' + 
						answer.get("confidence_score") + '\n');
			}
		}
		
		output.close();
	}
	
	public static void printResults(Map<String, SFQuery> queries) throws Exception {
		//TODO: for which slots were the documents with the answer retrieved and for which slots were not
	}
}





