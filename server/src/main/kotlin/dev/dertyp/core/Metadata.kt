package dev.dertyp.core

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.supports
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod

suspend infix fun IMetadataService.supports(funcType: Pair<KFunction<*>, IMetadataService.MetadataType>): Boolean {
    val (func, type) = funcType
    val feature = func.findAnnotation<IMetadataService.ProvidesFeature>()?.feature
        ?: func.javaMethod?.let { javaMethod ->
            val interfaceClass = IMetadataService::class.java
            try {
                interfaceClass.getMethod(javaMethod.name, *javaMethod.parameterTypes)
                    .getAnnotation(IMetadataService.ProvidesFeature::class.java)
            } catch (_: Exception) {
                null
            }
        }?.feature
        ?: return true
    
    return this.supports(type, feature)
}

infix fun KFunction<*>.at(type: IMetadataService.MetadataType) = this to type

class IMetadataServiceProvider(val service: IMetadataService, val type: IMetadataService.MetadataType)

infix fun IMetadataService.at(type: IMetadataService.MetadataType): IMetadataServiceProvider =
    IMetadataServiceProvider(this, type)

suspend infix fun IMetadataServiceProvider.supports(feature: IMetadataService.Feature): Boolean =
    service.supports(type, feature)

suspend infix fun IMetadataServiceProvider.supports(func: KFunction<*>): Boolean =
    service supports (func at type)
