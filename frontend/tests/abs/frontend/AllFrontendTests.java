/** 
 * Copyright (c) 2009-2011, The HATS Consortium. All rights reserved. 
 * This file is licensed under the terms of the Modified BSD License.
 */
package abs.frontend;

import abs.frontend.analyser.DuplicateCheckTest;
import abs.frontend.analyser.InterfaceDeclarationTest;
import abs.frontend.analyser.OtherAnalysisTests;
import abs.frontend.analyser.VarResolutionTest;
import abs.frontend.parser.*;
import abs.frontend.typesystem.AnnotationTests;
import abs.frontend.typesystem.AtomicityTests;
import abs.frontend.typesystem.ClassKindTests;
import abs.frontend.typesystem.ExamplesTypeChecking;
import abs.frontend.typesystem.LocationTypeTests;
import abs.frontend.typesystem.NegativeTypeCheckerTests;
import abs.frontend.typesystem.TypeCheckerTest;
import abs.frontend.typesystem.TypingTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        ParserTest.class, RecoverTest.class, DuplicateCheckTest.class, InterfaceDeclarationTest.class,
        ParseSamplesTest.class, VarResolutionTest.class, TypingTest.class, TypeCheckerTest.class,
        NegativeTypeCheckerTests.class, LocationTypeTests.class,
        ExamplesTypeChecking.class, AnnotationTests.class,
        ClassKindTests.class, OtherAnalysisTests.class, AtomicityTests.class,
        TestABSPackages.class})
public class AllFrontendTests {}
