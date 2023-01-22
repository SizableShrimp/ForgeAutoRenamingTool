/*
 * Forge Auto Renaming Tool
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fart.internal;

import net.minecraftforge.fart.api.Inheritance;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InnerClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

// This should always be applied after the remapper for best results.
// Proguard seems to remap local classes (classes inside of methods of the form Outer$(number)Inner) to the form a$a, conflicting with nested classes.
public final class InnerClassesAttributeFixer extends OptionalChangeTransformer {
    private final Inheritance inheritance;
    private final Map<String, List<InnerClassNode>> classes = new HashMap<>();

    public InnerClassesAttributeFixer(Inheritance inheritance) {
        super(null);
        this.inheritance = inheritance;
    }

    void setupClasses() {
        for (Optional<Inheritance.IClassInfo> opt : ((InheritanceImpl) this.inheritance).getClasses().values()) {
            if (!opt.isPresent())
                continue;

            Inheritance.IClassInfo classInfo = opt.get();
            String name = classInfo.getName();
            int dollarIdx = name.lastIndexOf('$');
            if (dollarIdx != -1) {
                final String outerName = name.substring(0, dollarIdx);
                String outerNameMut = name.substring(0, dollarIdx);
                String innerName = name.substring(dollarIdx + 1);
                boolean isAnonymous = Character.isDigit(innerName.charAt(0));
                // boolean isNested = !isAnonymous;
                boolean isLocal = false;
                int firstLetterIdx = -1;
                if (isAnonymous) {
                    // Check if there is a letter character anywhere after the digits, that makes it a local class (using standard compiler rules).
                    for (int i = 0; i < innerName.length(); i++) {
                        if (Character.isLetter(innerName.charAt(i))) {
                            isLocal = true;
                            firstLetterIdx = i;
                            break;
                        }
                    }
                }

                // Nested classes should keep the outerName and innerName already computed
                if (isLocal) {
                    outerNameMut = null;
                    innerName = innerName.substring(firstLetterIdx);
                } else if (isAnonymous) {
                    outerNameMut = null;
                    innerName = null;
                }

                // Three access flags we can never get back are ACC_PRIVATE, ACC_PROTECTED, and ACC_STATIC.
                // I don't think there is any way to guess on ACC_PRIVATE or ACC_PROTECTED.
                // We can guess on ACC_STATIC based on if the inner/local/nested class has a "this$0" field in it or not.
                // The compiler seems to add '$' to the end of the synthetic outer field until it is not in use
                // if the name is taken up by a declared field.
                // Should we detect this edge case? People naming their fields "this$0" are evil anyway.
                int access = (classInfo.getAccess() & ~Opcodes.ACC_SUPER); // ACC_SUPER is not a valid flag for inner_class_access_flags (JVMS 4.7.6)
                boolean isStatic = true;
                for (Inheritance.IFieldInfo fieldInfo : classInfo.getFields()) {
                    if ((fieldInfo.getAccess() & Opcodes.ACC_STATIC) == 0 && fieldInfo.getName().equals("this$0")) {
                        isStatic = false;
                        break;
                    }
                }
                if (isStatic)
                    access = access | Opcodes.ACC_STATIC;

                this.classes.computeIfAbsent(outerName, k -> new ArrayList<>()).add(new InnerClassNode(name, outerNameMut, innerName, access));
            }
        }
    }

    @Override
    protected Function<ClassVisitor, ClassFixer> getFixerFactory() {
        return Fixer::new;
    }

    private class Fixer extends ClassFixer {
        private String name;
        private boolean hasInnerClasses = false;

        public Fixer(ClassVisitor parent) {
            super(parent);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            this.hasInnerClasses = true;
            super.visitInnerClass(name, outerName, innerName, access);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitEnd() {
            if (!this.hasInnerClasses) {
                List<InnerClassNode> innerClasses = InnerClassesAttributeFixer.this.classes.get(this.name);
                if (innerClasses != null && !innerClasses.isEmpty()) {
                    for (InnerClassNode innerClassNode : innerClasses) {
                        super.visitInnerClass(innerClassNode.name, innerClassNode.outerName, innerClassNode.innerName, innerClassNode.access);
                    }
                    this.madeChange = true;
                }
            }

            super.visitEnd();
        }
    }
}
