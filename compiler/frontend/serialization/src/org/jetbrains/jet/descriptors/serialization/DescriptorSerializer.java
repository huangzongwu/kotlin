/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.descriptors.serialization;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.Annotated;
import org.jetbrains.jet.lang.types.*;
import org.jetbrains.jet.renderer.DescriptorRenderer;

import java.util.*;

public class DescriptorSerializer {

    private static final DescriptorRenderer RENDERER = DescriptorRenderer.STARTS_FROM_NAME;
    private static final Comparator<DeclarationDescriptor> DESCRIPTOR_COMPARATOR = new Comparator<DeclarationDescriptor>() {
        @Override
        public int compare(
                DeclarationDescriptor o1, DeclarationDescriptor o2
        ) {
            int names = o1.getName().compareTo(o2.getName());
            if (names != 0) return names;

            String o1String = RENDERER.render(o1);
            String o2String = RENDERER.render(o2);
            return o1String.compareTo(o2String);
        }
    };
    private final NameTable nameTable;
    private final Interner<TypeParameterDescriptor> typeParameters;
    private final Predicate<ClassDescriptor> isSpecial;

    public DescriptorSerializer(@NotNull NameTable.Namer namer) {
        this(namer, Predicates.<ClassDescriptor>alwaysFalse());
    }

    public DescriptorSerializer(@NotNull NameTable.Namer namer, @NotNull Predicate<ClassDescriptor> isSpecial) {
        this(new NameTable(namer), new Interner<TypeParameterDescriptor>(), isSpecial);
    }

    private DescriptorSerializer(NameTable nameTable, Interner<TypeParameterDescriptor> typeParameters, Predicate<ClassDescriptor> isSpecial) {
        this.nameTable = nameTable;
        this.typeParameters = typeParameters;
        this.isSpecial = isSpecial;
    }

    private DescriptorSerializer createChildSerializer() {
        return new DescriptorSerializer(nameTable, new Interner<TypeParameterDescriptor>(typeParameters), Predicates.<ClassDescriptor>alwaysFalse());
    }

    @NotNull
    public NameTable getNameTable() {
        return nameTable;
    }

    @NotNull
    public ProtoBuf.Class.Builder classProto(@NotNull ClassDescriptor classDescriptor) {
        ProtoBuf.Class.Builder builder = ProtoBuf.Class.newBuilder();

        int flags = Flags.getClassFlags(hasAnnotations(classDescriptor), classDescriptor.getVisibility(),
                                        classDescriptor.getModality(), classDescriptor.getKind(), classDescriptor.isInner());
        builder.setFlags(flags);

        // TODO extra visibility

        builder.setName(nameTable.getSimpleNameIndex(classDescriptor.getName()));

        DescriptorSerializer local = createChildSerializer();

        for (TypeParameterDescriptor typeParameterDescriptor : classDescriptor.getTypeConstructor().getParameters()) {
            builder.addTypeParameters(local.typeParameter(typeParameterDescriptor));
        }

        if (!isSpecial.apply(classDescriptor)) {
            // Special classes (Any, Nothing) have no supertypes
            for (JetType supertype : classDescriptor.getTypeConstructor().getSupertypes()) {
                builder.addSupertypes(local.type(supertype));
            }
        }

        ConstructorDescriptor primaryConstructor = classDescriptor.getUnsubstitutedPrimaryConstructor();
        if (primaryConstructor != null) {
            builder.setPrimaryConstructor(local.callableProto(primaryConstructor));
        }
        // TODO: other constructors

        for (DeclarationDescriptor descriptor : sort(classDescriptor.getDefaultType().getMemberScope().getAllDescriptors())) {
            if (descriptor instanceof CallableMemberDescriptor) {
                CallableMemberDescriptor member = (CallableMemberDescriptor) descriptor;
                if (member.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) continue;
                builder.addMembers(local.callableProto(member));
            }
        }

        Collection<DeclarationDescriptor> nestedClasses = classDescriptor.getUnsubstitutedInnerClassesScope().getAllDescriptors();
        for (DeclarationDescriptor descriptor : sort(nestedClasses)) {
            ClassDescriptor nestedClass = (ClassDescriptor) descriptor;
            int nameIndex = nameTable.getSimpleNameIndex(nestedClass.getName());
            builder.addNestedClassNames(nameIndex);
        }

        for (ClassDescriptor descriptor : sort(classDescriptor.getUnsubstitutedInnerClassesScope().getObjectDescriptors())) {
            int nameIndex = nameTable.getSimpleNameIndex(descriptor.getName());
            builder.addNestedObjectNames(nameIndex);
        }

        if (classDescriptor.getClassObjectDescriptor() != null) {
            // false is default
            builder.setClassObjectPresent(true);
        }

        return builder;
    }

    @NotNull
    public ProtoBuf.Callable.Builder callableProto(@NotNull CallableMemberDescriptor descriptor) {
        ProtoBuf.Callable.Builder builder = ProtoBuf.Callable.newBuilder();

        // TODO setter flags
        // TODO setter annotations
        builder.setFlags(Flags.getCallableFlags(hasAnnotations(descriptor), descriptor.getVisibility(),
                                                descriptor.getModality(),
                                                descriptor.getKind(),
                                                callableKind(descriptor),
                                                descriptor instanceof SimpleFunctionDescriptor &&
                                                ((SimpleFunctionDescriptor) descriptor).isInline())
        );
        //TODO builder.setExtraVisibility()

        //TODO builder.addAnnotations()

        DescriptorSerializer local = createChildSerializer();

        for (TypeParameterDescriptor typeParameterDescriptor : descriptor.getTypeParameters()) {
            builder.addTypeParameters(local.typeParameter(typeParameterDescriptor));
        }

        ReceiverParameterDescriptor receiverParameter = descriptor.getReceiverParameter();
        if (receiverParameter != null) {
            builder.setReceiverType(local.type(receiverParameter.getType()));
        }

        builder.setName(nameTable.getSimpleNameIndex(descriptor.getName()));

        for (ValueParameterDescriptor valueParameterDescriptor : descriptor.getValueParameters()) {
            builder.addValueParameters(local.valueParameter(valueParameterDescriptor));
        }

        builder.setReturnType(local.type(descriptor.getReturnType()));

        return builder;
    }

    private static ProtoBuf.Callable.CallableKind callableKind(CallableMemberDescriptor descriptor) {
        if (descriptor instanceof PropertyDescriptor) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;
            return propertyDescriptor.isVar() ? ProtoBuf.Callable.CallableKind.VAR : ProtoBuf.Callable.CallableKind.VAL;
        }
        if (descriptor instanceof ConstructorDescriptor) {
            return ProtoBuf.Callable.CallableKind.CONSTRUCTOR;
        }
        assert descriptor instanceof FunctionDescriptor : "Unknown descriptor class: " + descriptor.getClass();
        return ProtoBuf.Callable.CallableKind.FUN;
    }

    private ProtoBuf.Callable.ValueParameter.Builder valueParameter(ValueParameterDescriptor descriptor) {
        ProtoBuf.Callable.ValueParameter.Builder builder = ProtoBuf.Callable.ValueParameter.newBuilder();

        builder.setFlags(Flags.getValueParameterFlags(hasAnnotations(descriptor), descriptor.declaresDefaultValue()));

        builder.setName(nameTable.getSimpleNameIndex(descriptor.getName()));

        builder.setType(type(descriptor.getType()));

        JetType varargElementType = descriptor.getVarargElementType();
        if (varargElementType != null) {
            builder.setVarargElementType(type(varargElementType));
        }

        return builder;
    }

    private ProtoBuf.TypeParameter.Builder typeParameter(TypeParameterDescriptor typeParameter) {
        ProtoBuf.TypeParameter.Builder builder = ProtoBuf.TypeParameter.newBuilder();

        builder.setId(getTypeParameterId(typeParameter));

        builder.setName(nameTable.getSimpleNameIndex(typeParameter.getName()));

        // to avoid storing a default
        if (typeParameter.isReified()) {
            builder.setReified(true);
        }

        // to avoid storing a default
        ProtoBuf.TypeParameter.Variance variance = variance(typeParameter.getVariance());
        if (variance != ProtoBuf.TypeParameter.Variance.INV) {
            builder.setVariance(variance);
        }

        for (JetType upperBound : typeParameter.getUpperBounds()) {
            builder.addUpperBounds(type(upperBound));
        }

        return builder;
    }

    private static ProtoBuf.TypeParameter.Variance variance(Variance variance) {
        switch (variance) {
            case INVARIANT:
                return ProtoBuf.TypeParameter.Variance.INV;
            case IN_VARIANCE:
                return ProtoBuf.TypeParameter.Variance.IN;
            case OUT_VARIANCE:
                return  ProtoBuf.TypeParameter.Variance.OUT;
        }
        throw new IllegalStateException("Unknown variance: " + variance);
    }

    @NotNull
    public ProtoBuf.Type.Builder type(@NotNull JetType type) {
        assert !ErrorUtils.isErrorType(type) : "Can't serialize error types: " + type; // TODO

        ProtoBuf.Type.Builder builder = ProtoBuf.Type.newBuilder();

        builder.setConstructor(typeConstructor(type.getConstructor()));

        for (TypeProjection projection : type.getArguments()) {
            builder.addArguments(typeArgument(projection));
        }

        // to avoid storing a default
        if (type.isNullable()) {
            builder.setNullable(true);
        }

        return builder;
    }

    @NotNull
    private ProtoBuf.Type.Argument.Builder typeArgument(@NotNull TypeProjection typeProjection) {
        ProtoBuf.Type.Argument.Builder builder = ProtoBuf.Type.Argument.newBuilder();
        ProtoBuf.Type.Argument.Projection projection = projection(typeProjection.getProjectionKind());

        // to avoid storing a default
        if (projection != ProtoBuf.Type.Argument.Projection.INV) {
            builder.setProjection(projection);
        }

        builder.setType(type(typeProjection.getType()));
        return builder;
    }

    @NotNull
    private ProtoBuf.Type.Constructor.Builder typeConstructor(@NotNull TypeConstructor typeConstructor) {
        ProtoBuf.Type.Constructor.Builder builder = ProtoBuf.Type.Constructor.newBuilder();

        ClassifierDescriptor declarationDescriptor = typeConstructor.getDeclarationDescriptor();

        assert declarationDescriptor instanceof TypeParameterDescriptor || declarationDescriptor instanceof ClassDescriptor
                : "Unknown declaration descriptor: " + typeConstructor;
        if (declarationDescriptor instanceof TypeParameterDescriptor) {
            TypeParameterDescriptor typeParameterDescriptor = (TypeParameterDescriptor) declarationDescriptor;
            builder.setKind(ProtoBuf.Type.Constructor.Kind.TYPE_PARAMETER);
            builder.setId(getTypeParameterId(typeParameterDescriptor));
        }
        else {
            ClassDescriptor classDescriptor = (ClassDescriptor) declarationDescriptor;
            //default: builder.setKind(Type.Constructor.Kind.CLASS);
            builder.setId(getClassId(classDescriptor));
        }
        return builder;
    }

    @NotNull
    private static ProtoBuf.Type.Argument.Projection projection(@NotNull Variance projectionKind) {
        switch (projectionKind) {
            case INVARIANT:
                return ProtoBuf.Type.Argument.Projection.INV;
            case IN_VARIANCE:
                return ProtoBuf.Type.Argument.Projection.IN;
            case OUT_VARIANCE:
                return ProtoBuf.Type.Argument.Projection.OUT;
        }
        throw new IllegalStateException("Unknown projectionKind: " + projectionKind);
    }

    private int getClassId(@NotNull ClassDescriptor descriptor) {
        return nameTable.getFqNameIndex(descriptor);
    }

    private int getTypeParameterId(@NotNull TypeParameterDescriptor descriptor) {
        return typeParameters.intern(descriptor);
    }

    private static boolean hasAnnotations(Annotated descriptor) {
        return !descriptor.getAnnotations().isEmpty();
    }

    @NotNull
    public static <T extends DeclarationDescriptor> List<T> sort(@NotNull Collection<T> descriptors) {
        List<T> result = new ArrayList<T>(descriptors);
        Collections.sort(result, DESCRIPTOR_COMPARATOR);
        return result;

    }
}
