/*
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 2001 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Xerces" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation and was
 * originally based on software copyright (c) 2001, International
 * Business Machines, Inc., http://www.apache.org.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.xerces.impl.v2;

import org.apache.xerces.impl.XMLErrorReporter;
import org.apache.xerces.util.DOMUtil;
import org.apache.xerces.util.XInt;
import org.apache.xerces.util.XIntPool;
import org.apache.xerces.impl.v2.datatypes.*;
import org.apache.xerces.xni.QName;

import org.w3c.dom.Element;
import org.w3c.dom.Attr;
import org.w3c.dom.Node;

import java.lang.reflect.*;
import java.util.Stack;
import java.util.Hashtable;
import java.util.Vector;
import java.util.StringTokenizer;

/**
 * The simple type definition schema component traverser.
 * 
 * <simpleType
 *   final = (#all | (list | union | restriction))
 *   id = ID
 *   name = NCName
 *   {any attributes with non-schema namespace . . .}>
 *   Content: (annotation?, (restriction | list | union))
 * </simpleType>
 * 
 * <restriction
 *   base = QName
 *   id = ID
 *   {any attributes with non-schema namespace . . .}>
 *   Content: (annotation?, (simpleType?, (minExclusive | minInclusive | maxExclusive | maxInclusive | totalDigits | fractionDigits | length | minLength | maxLength | enumeration | whiteSpace | pattern)*))
 * </restriction>
 * 
 * <list
 *   id = ID
 *   itemType = QName
 *   {any attributes with non-schema namespace . . .}>
 *   Content: (annotation?, (simpleType?))
 * </list>
 * 
 * <union
 *   id = ID
 *   memberTypes = List of QName
 *   {any attributes with non-schema namespace . . .}>
 *   Content: (annotation?, (simpleType*))
 * </union>
 * 
 * @author Elena Litani, IBM
 * @version $Id$
 */
class XSDSimpleTypeTraverser extends XSDAbstractTraverser {

    //private data
    private Hashtable fFacetData = new Hashtable(10);
    private String fListName = "";

    private XSDocumentInfo fSchemaDoc = null;
    private SchemaGrammar fGrammar = null;
    private StringBuffer fPattern = null;
    private int fSimpleTypeAnonCount = 0;

    XSDSimpleTypeTraverser (XSDHandler handler,
                            XMLErrorReporter errorReporter,
                            XSAttributeChecker gAttrCheck) {
        super(handler, errorReporter, gAttrCheck);
    }

    //return qualified name of simpleType or empty string if error occured
    int traverseGlobal(Element elmNode,
                       XSDocumentInfo schemaDoc,
                       SchemaGrammar grammar) {
        // General Attribute Checking
        fSchemaDoc = schemaDoc;
        fGrammar = grammar;
        Object[] attrValues = fAttrChecker.checkAttributes(elmNode, true, schemaDoc.fNamespaceSupport);
        String nameAtt = (String)attrValues[XSAttributeChecker.ATTIDX_NAME];
        int typeIndex = traverseSimpleTypeDecl (elmNode, attrValues, schemaDoc, true);
        fAttrChecker.returnAttrArray(attrValues, schemaDoc.fNamespaceSupport);

        return typeIndex;
    }

    int traverseLocal(Element elmNode,
                      XSDocumentInfo schemaDoc,
                      SchemaGrammar grammar) {
        fSchemaDoc = schemaDoc;
        fGrammar = grammar;
        
        Object[] attrValues = fAttrChecker.checkAttributes(elmNode, false, schemaDoc.fNamespaceSupport);
        int typeIndex = traverseSimpleTypeDecl (elmNode, attrValues, schemaDoc, false);
        fAttrChecker.returnAttrArray(attrValues, schemaDoc.fNamespaceSupport);

        return typeIndex;
    }

    private int traverseSimpleTypeDecl(Element simpleTypeDecl, Object[] attrValues,
                                       XSDocumentInfo schemaDoc, boolean isGlobal) {

        String nameProperty  = (String)attrValues[XSAttributeChecker.ATTIDX_NAME];
        String qualifiedName = nameProperty;

        //---------------------------------------------------
        // set qualified name
        //---------------------------------------------------
        if (nameProperty == null) { // anonymous simpleType
            qualifiedName =  fSchemaDoc.fTargetNamespace+","+"#S#"+(fSimpleTypeAnonCount++);
            //REVISIT:
            // add to symbol table?
        }
        else {
            // this behaviour has been changed so that we neither
            // process unqualified names as if they came from the schemaforschema namespace nor
            // fail to pick up unqualified names from schemas with no
            // targetnamespace.  - NG
            //if (fTargetNSURIString.length () != 0) {
            qualifiedName = fSchemaDoc.fTargetNamespace+","+qualifiedName;
            //}
            //REVISIT:
            // add to symbol table?

        }

        //----------------------------------------------------------
        // REVISIT!
        // update _final_ registry
        //----------------------------------------------------------
        XInt finalAttr = (XInt)attrValues[XSAttributeChecker.ATTIDX_FINAL]; 
        int finalProperty = finalAttr == null ? 0 : finalAttr.intValue();

        //----------------------------------------------------------------------
        //annotation?,(list|restriction|union)
        //----------------------------------------------------------------------
        Element content = DOMUtil.getFirstChildElement(simpleTypeDecl);
        content = checkContent(simpleTypeDecl, content, false);
        if (content == null) {
            reportGenericSchemaError("no child element found for simpleType '"+ nameProperty+"'");
            return SchemaGrammar.I_EMPTY_DECL;
        }

        // General Attribute Checking
        Object[] contentAttrs = fAttrChecker.checkAttributes(content, false, schemaDoc.fNamespaceSupport);
        // REVISIT: when to return the array
        fAttrChecker.returnAttrArray(contentAttrs, schemaDoc.fNamespaceSupport);

        //----------------------------------------------------------------------
        //use content.getLocalName for the cases there "xsd:" is a prefix, ei. "xsd:list"
        //----------------------------------------------------------------------
        String varietyProperty =  DOMUtil.getLocalName(content);  //content.getLocalName();
        QName baseTypeName = null;
        Vector memberTypes = null;
        Vector dTValidators = null;
        int size = 0;
        boolean list = false;
        boolean union = false;
        boolean restriction = false;
        int numOfTypes = 0; //list/restriction = 1, union = "+"

        if (varietyProperty.equals(SchemaSymbols.ELT_LIST)) { //traverse List
            baseTypeName = (QName)contentAttrs[XSAttributeChecker.ATTIDX_ITEMTYPE];
            list = true;
            if (fListName.length() != 0) { // parent is <list> datatype
                reportCosListOfAtomic();
                return SchemaGrammar.I_EMPTY_DECL;
            }
            else {
                fListName = qualifiedName;
            }
        }
        else if (varietyProperty.equals(SchemaSymbols.ELT_RESTRICTION)) { //traverse Restriction
            baseTypeName = (QName)contentAttrs[XSAttributeChecker.ATTIDX_BASE];
            //content.getAttribute( SchemaSymbols.ATT_BASE );
            restriction= true;
        }
        else if (varietyProperty.equals(SchemaSymbols.ELT_UNION)) { //traverse union
            union = true;
            memberTypes = (Vector)contentAttrs[XSAttributeChecker.ATTIDX_MEMBERTYPES];
            //content.getAttribute( SchemaSymbols.ATT_MEMBERTYPES);
            if (memberTypes != null) {
                size = memberTypes.size();
            }
            else {
                size = 1; //at least one must be seen as <simpleType> decl
            }
            dTValidators = new Vector (size, 2);
        }
        else {
            Object[] args = { varietyProperty};
            fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN, 
                                       "FeatureUnsupported",
                                       args, XMLErrorReporter.SEVERITY_ERROR);
        }
        if (DOMUtil.getNextSiblingElement(content) != null) {
            // REVISIT: Localize
            reportGenericSchemaError("error in content of simpleType");
        }

        DatatypeValidator baseValidator = null;
        int simpleTypeIndex = SchemaGrammar.I_EMPTY_DECL;
        if (baseTypeName == null && memberTypes == null) {
            //---------------------------
            //must 'see' <simpleType>
            //---------------------------

            //content = {annotation?,simpleType?...}
            content = DOMUtil.getFirstChildElement(content);

            //check content (annotation?, ...)
            content = checkContent(simpleTypeDecl, content, false);
            if (content == null) {
                reportGenericSchemaError("no child element found for simpleType '"+ nameProperty+"'");
                return SchemaGrammar.I_EMPTY_DECL;
            }
            if (DOMUtil.getLocalName(content).equals( SchemaSymbols.ELT_SIMPLETYPE )) {
                simpleTypeIndex = traverseLocal(content, fSchemaDoc, fGrammar);
                if (simpleTypeIndex != SchemaGrammar.I_EMPTY_DECL) {
                    baseValidator = (DatatypeValidator)fGrammar.getTypeDecl(simpleTypeIndex); 
                    if (baseValidator != null && union) {
                        dTValidators.addElement((DatatypeValidator)baseValidator);
                    }
                }
                if (baseValidator == null) {
                    Object[] args = {content.getAttribute( SchemaSymbols.ATT_BASE )};
                    fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                               "UnknownBaseDatatype",
                                               args,
                                               XMLErrorReporter.SEVERITY_ERROR);
                    return SchemaGrammar.I_EMPTY_DECL;
                }
            }
            else {
                Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                           "ListUnionRestrictionError",
                                           args,
                                           XMLErrorReporter.SEVERITY_ERROR);
                return SchemaGrammar.I_EMPTY_DECL;
            }
        } //end - must see simpleType?
        else {
            //-----------------------------
            //base was provided - get proper validator.
            //-----------------------------
            numOfTypes = 1;
            if (union) {
                numOfTypes= size;
            }
            //--------------------------------------------------------------------
            // this loop is also where we need to find out whether the type being used as
            // a base (or itemType or whatever) allows such things.
            //--------------------------------------------------------------------
            int baseRefContext = (restriction? SchemaSymbols.RESTRICTION:0);
            baseRefContext = baseRefContext | (union? SchemaSymbols.UNION:0);
            baseRefContext = baseRefContext | (list ? SchemaSymbols.LIST:0);
            for (int i=0; i<numOfTypes; i++) {  //find all validators
                if (union) {
                    baseTypeName = (QName)memberTypes.elementAt(i);
                }
                baseValidator = findDTValidator ( simpleTypeDecl, baseTypeName, baseRefContext);
                if (baseValidator == null) {
                    reportGenericSchemaError("base type not found: '"+baseTypeName.uri+","+baseTypeName.localpart+"'");
                    baseValidator = (DatatypeValidator)SchemaGrammar.SG_SchemaNS.getTypeDecl(SchemaSymbols.ATTVAL_STRING);
                }
                // ------------------------------
                // (variety is list)cos-list-of-atomic
                // ------------------------------
                if (fListName.length() != 0) {
                    if (baseValidator instanceof ListDatatypeValidator) {
                        reportCosListOfAtomic();
                        return SchemaGrammar.I_EMPTY_DECL;
                    }
                    //-----------------------------------------------------
                    // if baseValidator is of type (union) need to look
                    // at Union validators to make sure that List is not one of them
                    //-----------------------------------------------------
                    if (isListDatatype(baseValidator)) {
                        reportCosListOfAtomic();
                        return SchemaGrammar.I_EMPTY_DECL;

                    }

                }
                if (union) {
                    dTValidators.addElement((DatatypeValidator)baseValidator); //add validator to structure
                }
            }
        } //end - base is available


        // ------------------------------------------
        // move to next child
        // <base==empty)->[simpleType]->[facets]  OR
        // <base!=empty)->[facets]
        // ------------------------------------------
        if (baseTypeName == null) {
            content = DOMUtil.getNextSiblingElement( content );
        }
        else {
            content = DOMUtil.getFirstChildElement(content);
        }

        // ------------------------------------------
        //get more types for union if any
        // ------------------------------------------
        if (union) {
            int index=size;
            if (memberTypes != null) {
                content = checkContent(simpleTypeDecl, content, true);
            }
            while (content!=null) {
                simpleTypeIndex = traverseLocal(content, fSchemaDoc, fGrammar);
                baseValidator = null;
                if (simpleTypeIndex != SchemaGrammar.I_EMPTY_DECL) {
                    baseValidator= (DatatypeValidator)fGrammar.getTypeDecl(simpleTypeIndex);
                    if (baseValidator != null) {
                        if (fListName.length() != 0 && baseValidator instanceof ListDatatypeValidator) {
                            reportCosListOfAtomic();
                            return SchemaGrammar.I_EMPTY_DECL;
                        }
                        dTValidators.addElement((DatatypeValidator)baseValidator);
                    }
                }
                if (baseValidator == null) {
                    Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_BASE ), simpleTypeDecl.getAttribute(SchemaSymbols.ATT_NAME)};
                    fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                               "UnknownBaseDatatype",
                                               args,
                                               XMLErrorReporter.SEVERITY_ERROR);
                    return SchemaGrammar.I_EMPTY_DECL;
                }
                content   = DOMUtil.getNextSiblingElement( content );
            }
        } // end - traverse Union


        if (fListName.length() != 0) {
            // reset fListName, meaning that we are done with
            // traversing <list> and its itemType resolves to atomic value
            if (fListName.equals(qualifiedName)) {
                fListName = "";
            }
        }

        int numFacets=0;
        fFacetData.clear();
        if (restriction && content != null) {
            short flags = 0; // flag facets that have fixed="true"
            int numEnumerationLiterals = 0;
            Vector enumData  = new Vector();
            content = checkContent(simpleTypeDecl, content , true);
            String facet;
            while (content != null) {
                // General Attribute Checking
                Object[] attrs = fAttrChecker.checkAttributes(content, false, schemaDoc.fNamespaceSupport);
                numFacets++;
                facet = DOMUtil.getLocalName(content);
                if (facet.equals(SchemaSymbols.ELT_ENUMERATION)) {
                    numEnumerationLiterals++;
                    String enumVal =  DOMUtil.getAttrValue(content, SchemaSymbols.ATT_VALUE);
                    String localName;
                    if (baseValidator instanceof NOTATIONDatatypeValidator) {
                        String prefix = "";
                        String localpart = enumVal;
                        int colonptr = enumVal.indexOf(":");
                        if (colonptr > 0) {
                            prefix = enumVal.substring(0,colonptr);
                            localpart = enumVal.substring(colonptr+1);
                        }
                        String uriStr = (prefix.length()!=0) ? schemaDoc.fNamespaceSupport.getURI(prefix):fSchemaDoc.fTargetNamespace;
                        nameProperty=uriStr + ":" + localpart;
                        localName = (String)fSchemaHandler.fNotationRegistry.get(nameProperty);
                        if (localName == null) {

                            //REVISIT: when implementing notation!
                            //localName = traverseNotationFromAnotherSchema( localpart, uriStr);
                            if (localName == null) {
                                reportGenericSchemaError("Notation '" + localpart +
                                                         "' not found in the grammar "+ uriStr);

                            }
                        }
                        enumVal=nameProperty;
                    }
                    enumData.addElement(enumVal);
                    checkContent(simpleTypeDecl, DOMUtil.getFirstChildElement( content ), true);
                }
                else if (facet.equals(SchemaSymbols.ELT_ANNOTATION) || facet.equals(SchemaSymbols.ELT_SIMPLETYPE)) {
                    //REVISIT: 
                    Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                    fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                               "ListUnionRestrictionError",
                                               args,
                                               XMLErrorReporter.SEVERITY_ERROR);

                }
                else if (facet.equals(SchemaSymbols.ELT_PATTERN)) {
                    if (fPattern == null) {
                        //REVISIT: size of buffer
                        fPattern = new StringBuffer (DOMUtil.getAttrValue( content, SchemaSymbols.ATT_VALUE ));
                    }
                    else {
                        // ---------------------------------------------
                        //datatypes: 5.2.4 pattern: src-multiple-pattern
                        // ---------------------------------------------
                        fPattern.append("|");
                        fPattern.append(DOMUtil.getAttrValue(content, SchemaSymbols.ATT_VALUE ));
                        checkContent(simpleTypeDecl, DOMUtil.getFirstChildElement( content ), true);
                    }
                }
                else {
                    if (fFacetData.containsKey(facet))
                        fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                                   "DatatypeError",
                                                   new Object[]{"The facet '" + facet + "' is defined more than once."},
                                                   XMLErrorReporter.SEVERITY_ERROR);
                    fFacetData.put(facet,content.getAttribute( SchemaSymbols.ATT_VALUE ));

                    if (content.getAttribute( SchemaSymbols.ATT_FIXED).equals(SchemaSymbols.ATTVAL_TRUE) ||
                        content.getAttribute( SchemaSymbols.ATT_FIXED).equals(SchemaSymbols.ATTVAL_TRUE_1)) {
                        // --------------------------------------------
                        // set fixed facet flags
                        // length - must remain const through derivation
                        // thus we don't care if it fixed
                        // --------------------------------------------
                        if (facet.equals(SchemaSymbols.ELT_MINLENGTH)) {
                            flags |= DatatypeValidator.FACET_MINLENGTH;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_MAXLENGTH)) {
                            flags |= DatatypeValidator.FACET_MAXLENGTH;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_MAXEXCLUSIVE)) {
                            flags |= DatatypeValidator.FACET_MAXEXCLUSIVE;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_MAXINCLUSIVE)) {
                            flags |= DatatypeValidator.FACET_MAXINCLUSIVE;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_MINEXCLUSIVE)) {
                            flags |= DatatypeValidator.FACET_MINEXCLUSIVE;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_MININCLUSIVE)) {
                            flags |= DatatypeValidator.FACET_MININCLUSIVE;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_TOTALDIGITS)) {
                            flags |= DatatypeValidator.FACET_TOTALDIGITS;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_FRACTIONDIGITS)) {
                            flags |= DatatypeValidator.FACET_FRACTIONDIGITS;
                        }
                        else if (facet.equals(SchemaSymbols.ELT_WHITESPACE) &&
                                 baseValidator instanceof StringDatatypeValidator) {
                            flags |= DatatypeValidator.FACET_WHITESPACE;
                        }
                    }
                    checkContent(simpleTypeDecl, DOMUtil.getFirstChildElement( content ), true);
                }
                // REVISIT: when to return the array
                fAttrChecker.returnAttrArray(attrs, schemaDoc.fNamespaceSupport);
                content = DOMUtil.getNextSiblingElement(content);
            }
            if (numEnumerationLiterals > 0) {
                fFacetData.put(SchemaSymbols.ELT_ENUMERATION, enumData);
            }
            if (fPattern !=null) {
                fFacetData.put(SchemaSymbols.ELT_PATTERN, fPattern.toString());
            }
            if (flags != 0) {
                fFacetData.put(DatatypeValidator.FACET_FIXED, new Short(flags));
            }
            fPattern = null;
        }
        else if (list && content!=null) {
            // report error - must not have any children!
            if (baseTypeName != null) {
                content = checkContent(simpleTypeDecl, content, true);
                if (content!=null) {
                    //REVISIT:
                    Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                    fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                               "ListUnionRestrictionError",
                                               args,
                                               XMLErrorReporter.SEVERITY_ERROR);

                }
            }
            else {
                Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                           "ListUnionRestrictionError",
                                           args,
                                           XMLErrorReporter.SEVERITY_ERROR);
            }
        }
        else if (union && content!=null) {
            //report error - must not have any children!
            if (memberTypes != null) {
                content = checkContent(simpleTypeDecl, content, true);
                if (content!=null) {
                    Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                    fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                               "ListUnionRestrictionError",
                                               args,
                                               XMLErrorReporter.SEVERITY_ERROR);
                }
            }
            else {
                Object[] args = { simpleTypeDecl.getAttribute( SchemaSymbols.ATT_NAME )};
                fErrorReporter.reportError(XSMessageFormatter.SCHEMA_DOMAIN,
                                           "ListUnionRestrictionError",
                                           args,
                                           XMLErrorReporter.SEVERITY_ERROR);
            }
        }

        DatatypeValidator newDV = null;
        if (list) {
            try {
                newDV = new ListDatatypeValidator(baseValidator, fFacetData, true);
            } catch (InvalidDatatypeFacetException e) {
                reportGenericSchemaError(e.getMessage());
            }
        }
        else if (restriction) {
            Class validatorDef = baseValidator.getClass();
            Class [] validatorArgsClass = new Class[] {
                org.apache.xerces.impl.v2.datatypes.DatatypeValidator.class,
                java.util.Hashtable.class,
                boolean.class};

            Object [] validatorArgs = new Object[] {baseValidator, fFacetData, Boolean.FALSE};
            try {
                Constructor validatorConstructor = validatorDef.getConstructor( validatorArgsClass );
                newDV = (DatatypeValidator) validatorConstructor.newInstance(validatorArgs);
            } catch (NoSuchMethodException e) {
            } catch ( InstantiationException e ) {
            } catch ( IllegalAccessException e ) {
            } catch ( IllegalArgumentException e ) {
            } catch ( InvocationTargetException e ) {
                reportGenericSchemaError(e.getMessage());
            }
        }
        else { //union
            newDV = new UnionDatatypeValidator(dTValidators);
        }
        newDV.setFinalSet(finalProperty);
        //REVISIT: add type name into type
        //int newDVIdx = fGrammar.addTypeDecl(nameProperty, newDV);
        ((AbstractDatatypeValidator)newDV).fLocalName = nameProperty;
        int newDVIdx = fGrammar.addTypeDecl(newDV, isGlobal);

        return newDVIdx;
    }


    private void reportCosListOfAtomic () {
        reportGenericSchemaError("cos-list-of-atomic: The itemType must have a {variety} of atomic or union (in which case all the {member type definitions} must be atomic)");
        fListName="";
    }

    //@param: elm - top element
    //@param: baseTypeStr - type (base/itemType/memberTypes)
    //@param: baseRefContext:  whether the caller is using this type as a base for restriction, union or list
    //return DatatypeValidator available for the baseTypeStr, null if not found or disallowed.
    // also throws an error if the base type won't allow itself to be used in this context.
    // REVISIT: can this code be re-used?
    private DatatypeValidator findDTValidator (Element elm, QName baseTypeStr, int baseRefContext ) {
        if (baseTypeStr.uri.equals(SchemaSymbols.URI_SCHEMAFORSCHEMA) &&
            baseTypeStr.localpart.equals(SchemaSymbols.ATTVAL_ANYSIMPLETYPE) &&
            baseRefContext == SchemaSymbols.RESTRICTION) {
            //REVISIT
            //reportSchemaError(SchemaMessageProvider.UnknownBaseDatatype,
            //                  new Object [] { DOMUtil.getAttrValue(elm, SchemaSymbols.ATT_BASE),
            //                      DOMUtil.getAttrValue(elm, SchemaSymbols.ATT_NAME)});
            return null;
        }
        DatatypeValidator baseValidator = null;
        int baseDVIndex = fSchemaHandler.getGlobalDecl(fSchemaDoc, fSchemaHandler.TYPEDECL_TYPE, baseTypeStr);
        if (baseDVIndex == SchemaGrammar.I_NOT_FOUND) {
            //REVISIT
            //reportSchemaError(SchemaMessageProvider.UnknownBaseDatatype,
            //                  new Object [] { DOMUtil.getAttrValue(elm, SchemaSymbols.ATT_BASE ),
            //                      DOMUtil.getAttrValue(elm,SchemaSymbols.ATT_NAME)});
        }
        else {
            baseValidator = (DatatypeValidator)fSchemaHandler.getXSTypeDecl(baseTypeStr.uri, baseDVIndex);
            int finalValue = baseValidator.getFinalSet();
            if ((finalValue & baseRefContext) != 0) {
                //REVISIT:  localize
                reportGenericSchemaError("the base type " + baseTypeStr.rawname + " does not allow itself to be used as the base for a restriction and/or as a type in a list and/or union");
                return baseValidator;
            }
        }
        return baseValidator;
    }

    // find if union datatype validator has list datatype member.
    private boolean isListDatatype (DatatypeValidator validator) {
        if (validator instanceof UnionDatatypeValidator) {
            Vector temp = ((UnionDatatypeValidator)validator).getBaseValidators();
            for (int i=0;i<temp.size();i++) {
                if (temp.elementAt(i) instanceof ListDatatypeValidator) {
                    return true;
                }
                if (temp.elementAt(i) instanceof UnionDatatypeValidator) {
                    if (isListDatatype((DatatypeValidator)temp.elementAt(i))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
