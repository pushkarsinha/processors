package edu.arizona.sista.processors.bionlp

import java.util
import java.util.Properties
import java.util.regex.Pattern

import edu.arizona.sista.processors.bionlp.ner.{BioNER, RuleNER}
import edu.arizona.sista.processors.{Sentence, Document}
import edu.arizona.sista.processors.corenlp.CoreNLPProcessor
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, TokensAnnotation}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap

import scala.collection.JavaConversions._

import BioNLPProcessor._

/**
 * A processor for biomedical texts, based on CoreNLP, but with different tokenization and NER
 * User: mihais
 * Date: 10/27/14
 */
class BioNLPProcessor (internStrings:Boolean = true,
                       withCRFNER:Boolean = true,
                       withRuleNER:Boolean = true,
                       withDiscourse:Boolean = false,
                       maxSentenceLength:Int = 100,
                       removeFigTabReferences:Boolean = true)
  extends CoreNLPProcessor(internStrings, basicDependencies = false, withDiscourse, maxSentenceLength) {

  //lazy val banner = new BannerWrapper
  lazy val postProcessor = new BioNLPTokenizerPostProcessor
  lazy val bioNer = BioNER.load(CRF_MODEL_PATH)
  lazy val ruleNer = RuleNER.load(RULE_NER_KBS)

  override def mkTokenizerWithoutSentenceSplitting: StanfordCoreNLP = {
    val props = new Properties()
    props.put("annotators", "tokenize")
    addBioTokenizerOptions(props)
    new StanfordCoreNLP(props)
  }

  override def mkTokenizerWithSentenceSplitting: StanfordCoreNLP = {
    val props = new Properties()
    props.put("annotators", "tokenize, ssplit")
    addBioTokenizerOptions(props)
    new StanfordCoreNLP(props)
  }

  def addBioTokenizerOptions(props:Properties) {
    props.put("tokenize.options", "ptb3Escaping=false")
    props.put("tokenize.language", "English")
  }

  /**
   * Implements the bio-specific post-processing steps from McClosky et al. (2011)
   * @param originalTokens Input CoreNLP sentence
   * @return The modified tokens
   */
  override def postprocessTokens(originalTokens:Array[CoreLabel]) = postProcessor.process(originalTokens)

  /**
   * Removes Figure and Table references that appear within parentheses
   * @param origText The original input text
   * @return The preprocessed text
   */
  override def preprocessText(origText:String):String = {
    if (!removeFigTabReferences) return origText

    var noRefs = origText
    // the pattern with parens must run first!
    noRefs = removeFigTabRefs(BioNLPProcessor.FIGTAB_REFERENCE_WITH_PARENS, noRefs)
    noRefs = removeFigTabRefs(BioNLPProcessor.FIGTAB_REFERENCE, noRefs)
    noRefs
  }

  /**
   * Removes references to Tables and Figures
   * @param pattern Fig/Tab pattern
   * @param text The original text
   * @return The cleaned text
   */
  def removeFigTabRefs(pattern:Pattern, text:String):String = {
    val m = pattern.matcher(text)
    val b = new StringBuilder
    var previousEnd = 0
    while(m.find()) {
      b.append(text.substring(previousEnd, m.start()))
      // white out the reference, keeping the same number of characters
      for(i <- m.start() until m.end()) b.append(" ")
      previousEnd = m.end()
    }
    if(previousEnd < text.length)
      b.append(text.substring(previousEnd))
    b.toString()
  }

  override def resolveCoreference(doc:Document): Unit = {
    // TODO: add domain-specific coreference here!
    doc.coreferenceChains = None
  }

  /**
   * Improve POS tagging in the Bio domain
   * @param annotation The CoreNLP annotation
   */
  override def postprocessTags(annotation:Annotation) {
    val sas = annotation.get(classOf[SentencesAnnotation])

    sas.foreach{ sa =>
      val tas = sa.get(classOf[TokensAnnotation])
      tas.foreach{ ta =>
        val text = ta.originalText().toLowerCase
        // some of our would-be verbs are mistagged...
        text match {
          case ubiq if ubiq.endsWith("ubiquitinates") => ta.setTag("VBZ")
          case ubiqNom if ubiqNom.endsWith("ubiquitinate") => ta.setTag("VB")
          case hydro if hydro.endsWith("hydrolyzes") => ta.setTag("VBZ")
          case _ => ()
        }
      }

    }
  }

  override def recognizeNamedEntities(doc:Document) {
    val annotation = namedEntitySanityCheck(doc)
    if(annotation.isEmpty) return

    if(withRuleNER) {
      // run the rule-based NER on one sentence at a time
      for(sentence <- doc.sentences) {
        sentence.entities = Some(ruleNer.find(sentence))
      }
    }

    if (withCRFNER) {
      // run the CRF NER on one sentence at a time
      // we are traversing our sentences and the CoreNLP sentences in parallel here!
      val sas = annotation.get.get(classOf[SentencesAnnotation])
      var sentenceOffset = 0
      for (sa: CoreMap <- sas) {
        val ourSentence = doc.sentences(sentenceOffset) // our sentence
        val coreNLPSentence = sa.get(classOf[TokensAnnotation]) // the CoreNLP sentence

        // build the NER input
        val inputSent = mkSent(ourSentence)

        // the actual sequence classification
        val bioNEs = bioNer.classify(inputSent).toArray

        // store labels in the CoreNLP annotation for the sentence
        var labelOffset = 0
        for (token <- coreNLPSentence) {
          token.setNER(bioNEs(labelOffset))
          labelOffset += 1
        }

        // store labels in our sentence
        // the rule-based NER labels take priority!
        if(ourSentence.entities.isDefined) {
          RuleNER.mergeLabels(ourSentence.entities.get, bioNEs)
        } else {
          ourSentence.entities = Some(bioNEs)
        }

        // TODO: we should have s.norms as well...

        sentenceOffset += 1
      }
    }
  }

  def mkSent(sentence:Sentence):util.List[CoreLabel] = {
    val output = new util.ArrayList[CoreLabel]()
    for(i <- 0 until sentence.size) {
      val l = new CoreLabel()
      l.setWord(sentence.words(i))
      l.setTag(sentence.tags.get(i))
      l.setLemma(sentence.lemmas.get(i))
      output.add(l)
    }
    output
  }
}

object BioNLPProcessor {
  val FIGTAB_REFERENCE_WITH_PARENS = Pattern.compile("\\((\\s*see)?\\s*(figure|table|fig\\.|tab\\.)[^\\)]*\\)", Pattern.CASE_INSENSITIVE)
  val FIGTAB_REFERENCE = Pattern.compile("\\s*see\\s*(figure|table|fig\\.|tab\\.)\\s*[0-9A-Za-z\\.]+", Pattern.CASE_INSENSITIVE)
  val CRF_MODEL_PATH = "edu/arizona/sista/processors/bionlp/ner/bioner.dat"

  val RULE_NER_KBS = List( // knowledge for the rule-based NER
    "edu/arizona/sista/processors/bionlp/ner/Gene_or_gene_product.tsv"
  )

  val NORMALIZED_LABELS = Map[String, String]( // needed to convert  the CRF's labels (from BioCreative) to our labels
    "B-GENE" -> "B-Gene_or_gene_product",
    "I-GENE" -> "I-Gene_or_gene_product"
  )
}

