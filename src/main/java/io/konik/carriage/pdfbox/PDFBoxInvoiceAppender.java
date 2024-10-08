/* Copyright (C) 2014 konik.io
 *
 * This file is part of the Konik library.
 *
 * The Konik library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * The Konik library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with the Konik library. If not, see <http://www.gnu.org/licenses/>.
 */
package io.konik.carriage.pdfbox;

import static java.util.Collections.singletonMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Scanner;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.xml.transform.TransformerException;

import io.konik.carriage.utils.ProducerAppendParameter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAExtensionSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.type.BadFieldValueException;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpParsingException;
import org.apache.xmpbox.xml.XmpSerializer;

import io.konik.carriage.pdfbox.exception.NotPDFAException;
import io.konik.carriage.pdfbox.xmp.XMPSchemaZugferd1p0;
import io.konik.carriage.utils.ByteCountingInputStream;
import io.konik.harness.AppendParameter;
import io.konik.harness.FileAppender;
import io.konik.harness.exception.InvoiceAppendError;

/**
 * ZUGFeRD PDFBox Invoice Appender.
 */
@Named
@Singleton
public class PDFBoxInvoiceAppender implements FileAppender {

   private static final int PRIORITY = 50;
   private static final String DEFAULT_PRODUCER = "Konik Library with PDFBox-Carriage";
   private static final String MIME_TYPE = "text/xml";
   private static final String ZF_FILE_NAME = "ZUGFeRD-invoice.xml";
   private static final String USER_NAME_KEY = "user.name";
   private static final String PDF_AUTHOR_KEY = "io.konik.carriage.pdf.author";
   private final XMPMetadata zfDefaultXmp;

   /**
    * Instantiates a new PDF box invoice appender.
    */
   public PDFBoxInvoiceAppender() {
      try {
         InputStream zfExtensionIs = getClass().getResourceAsStream("/zf_extension.pdfbox.xmp");
         DomXmpParser builder = new DomXmpParser();
         builder.setStrictParsing(true);
         zfDefaultXmp = builder.parse(zfExtensionIs);
         XMPSchema schema = zfDefaultXmp.getSchema(PDFAExtensionSchema.class);
         schema.addNamespace("http://www.aiim.org/pdfa/ns/schema#", "pdfaSchema");
         schema.addNamespace("http://www.aiim.org/pdfa/ns/property#", "pdfaProperty");
      } catch (XmpParsingException e) {
         throw new InvoiceAppendError("Error initializing PDFBoxInvoiceAppender", e);
      }
   }

   @Override
   public void append(AppendParameter appendParameter) {
      InputStream inputPdf = appendParameter.inputPdf();
      PDDocument doc = null;
      try {
         doc = Loader.loadPDF(inputPdf.readAllBytes());
         doc.setAllSecurityToBeRemoved(true);
         checkisPdfA(doc);
         convertToPdfA3(doc);
         setMetadata(doc, appendParameter);
         attachZugferdFile(doc, appendParameter.attachmentFile());
         doc.getDocument().setVersion(1.7f);
         doc.save(appendParameter.resultingPdf());         
      } catch (Exception e) {
         throw new InvoiceAppendError("Error appending Invoice the input stream is: " + inputPdf, e);
      }finally {
         if (doc != null) try {
            doc.close();
         } catch (IOException e) {
            throw new InvoiceAppendError("Could not close PDF Document", e);
         }
      }
   }

   protected void checkisPdfA(PDDocument doc) {
      PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
      if (metadata != null) {
         try {
            InputStream inputStream = metadata.createInputStream();
            Scanner streamScanner = new Scanner(inputStream);
            String found = streamScanner.findWithinHorizon("http://www.aiim.org/pdfa/ns/id", 0);
            streamScanner.close();
            if (found == null) { throw new NotPDFAException(); }
         } catch (IOException e) {
            throw new InvoiceAppendError("Could not read PDF Metadata", e);
         }
      }
   }

   protected void convertToPdfA3(PDDocument document) throws Exception {

   }

   private void setMetadata(PDDocument doc, AppendParameter appendParameter) throws IOException, TransformerException,
         BadFieldValueException {

      String producer = DEFAULT_PRODUCER;
      if (appendParameter instanceof ProducerAppendParameter){
         producer = ((ProducerAppendParameter)appendParameter).getProducer();
      }
      Calendar now = Calendar.getInstance();
      PDDocumentCatalog catalog = doc.getDocumentCatalog();

      PDMetadata metadata = new PDMetadata(doc);
      catalog.setMetadata(metadata);

      XMPMetadata xmp = XMPMetadata.createXMPMetadata();
      PDFAIdentificationSchema pdfaid = new PDFAIdentificationSchema(xmp);
      pdfaid.setPart(Integer.valueOf(3));
      pdfaid.setConformance("B");
      xmp.addSchema(pdfaid);

      DublinCoreSchema dublicCore = new DublinCoreSchema(xmp);
      xmp.addSchema(dublicCore);

      XMPBasicSchema basicSchema = new XMPBasicSchema(xmp);
      basicSchema.setCreatorTool(producer);
      basicSchema.setCreateDate(now);
      xmp.addSchema(basicSchema);

      PDDocumentInformation pdi = doc.getDocumentInformation();
      pdi.setModificationDate(now);
      pdi.setProducer(producer);
      pdi.setAuthor(getAuthor());
      doc.setDocumentInformation(pdi);

      AdobePDFSchema pdf = new AdobePDFSchema(xmp);
      pdf.setProducer(producer);
      xmp.addSchema(pdf);

      PDMarkInfo markinfo = new PDMarkInfo();
      markinfo.setMarked(true);
      doc.getDocumentCatalog().setMarkInfo(markinfo);

      xmp.addSchema(zfDefaultXmp.getPDFExtensionSchema());
      XMPSchemaZugferd1p0 zf = new XMPSchemaZugferd1p0(xmp);
      zf.setConformanceLevel(appendParameter.zugferdConformanceLevel());
      zf.setVersion(appendParameter.zugferdVersion());
      xmp.addSchema(zf);

      OutputStream outputStreamMeta = metadata.createOutputStream();

      new XmpSerializer().serialize(xmp, outputStreamMeta, true);

      outputStreamMeta.close();
   }

   private static void attachZugferdFile(PDDocument doc, InputStream zugferdFile) throws IOException {
      PDEmbeddedFilesNameTreeNode fileNameTreeNode = new PDEmbeddedFilesNameTreeNode();

      PDEmbeddedFile embeddedFile = createEmbeddedFile(doc, zugferdFile);
      PDComplexFileSpecification fileSpecification = createFileSpecification(embeddedFile);

      COSDictionary dict = fileSpecification.getCOSObject();
      dict.setName("AFRelationship", "Alternative");
      dict.setString("UF", ZF_FILE_NAME);

      fileNameTreeNode.setNames(singletonMap(ZF_FILE_NAME, fileSpecification));

      setNamesDictionary(doc, fileNameTreeNode);

      COSArray cosArray = new COSArray();
      cosArray.add(fileSpecification);
      doc.getDocumentCatalog().getCOSObject().setItem("AF", cosArray);
   }

   private static PDComplexFileSpecification createFileSpecification(PDEmbeddedFile embeddedFile) {
      PDComplexFileSpecification fileSpecification = new PDComplexFileSpecification();
      fileSpecification.setFile(ZF_FILE_NAME);
      fileSpecification.setEmbeddedFile(embeddedFile);
      fileSpecification.setFileDescription("ZUGFeRD Invoice created with Konik Library");
      return fileSpecification;
   }

   private static PDEmbeddedFile createEmbeddedFile(PDDocument doc, InputStream zugferdFile) throws IOException {
      Calendar now = Calendar.getInstance();
      ByteCountingInputStream countingIs = new ByteCountingInputStream(zugferdFile);
      PDEmbeddedFile embeddedFile = new PDEmbeddedFile(doc, countingIs);
      embeddedFile.setSubtype(MIME_TYPE);
      embeddedFile.setSize(countingIs.getByteCount());
      embeddedFile.setCreationDate(now);
      embeddedFile.setModDate(now);
      return embeddedFile;
   }

   private static void setNamesDictionary(PDDocument doc, PDEmbeddedFilesNameTreeNode fileNameTreeNode) {
      PDDocumentCatalog documentCatalog = doc.getDocumentCatalog();
      PDDocumentNameDictionary namesDictionary = new PDDocumentNameDictionary(documentCatalog);
      namesDictionary.setEmbeddedFiles(fileNameTreeNode);
      documentCatalog.setNames(namesDictionary);
   }

   private static String getAuthor() {
      String defaultAuthor = getDefaultAuthor();
      return Configuration.INSTANCE.getProperty(PDF_AUTHOR_KEY, defaultAuthor);

   }

   private static String getDefaultAuthor() {
      if (System.getProperty(PDF_AUTHOR_KEY) != null) { return System.getProperty(PDF_AUTHOR_KEY); }
      return System.getProperty(USER_NAME_KEY);
   }

   @Override
   public int getPriority() {
      return PRIORITY;
   }

}
