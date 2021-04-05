<#import "code_oid_lookup.ftl" as lookup>
<#macro code_section codes section counter tag="code" extra="">
<#if codes?has_content>
            <#-- Initialize the seen code list with the primary code as sometimes the primary is duplicated for translation. Otherwise only skip translation codes that occur more than once -->
            <#assign seen_codes = [ codes[0].code ] />
            <${tag} ${extra} code="${codes[0].code}" codeSystem="<@lookup.oid_for_code_system system=codes[0].system/>" displayName="${codes[0].display}">
              <originalText><reference value="#${section}-desc-#{counter}"/></originalText>       
            <#if codes?size gt 1>
              <#list codes[1..] as code>
                <#if seen_codes?seq_contains(code.code)>
                  <#-- Skip this 'translation' as it has already been output -->
                <#else>
              <translation code="${code.code}" codeSystem="<@lookup.oid_for_code_system system=code.system/>" displayName="${code.display}"/>
                  <#-- Add this code to our list of 'seen' codes so we do not generate duplicate translations -->
                  <#assign seen_codes = seen_codes + [ code.code ] />
                </#if>
              </#list>
            </#if>
            </${tag}>
<#else>
            <${tag} nullFlavor="UNK"/>
</#if>
</#macro>
