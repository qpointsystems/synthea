package org.mitre.synthea.export;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.datatype.CWE;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.IS;
import ca.uhn.hl7v2.model.v251.datatype.NM;
import ca.uhn.hl7v2.model.v251.datatype.ST;
import ca.uhn.hl7v2.model.v251.datatype.TS;
import ca.uhn.hl7v2.model.v251.datatype.XAD;
import ca.uhn.hl7v2.model.v251.datatype.XCN;
import ca.uhn.hl7v2.model.v251.datatype.XON;
import ca.uhn.hl7v2.model.v251.datatype.XPN;
import ca.uhn.hl7v2.model.v251.datatype.XTN;
import ca.uhn.hl7v2.model.v251.group.ADT_A03_INSURANCE;
import ca.uhn.hl7v2.model.v251.group.ADT_A03_PROCEDURE;
import ca.uhn.hl7v2.model.v251.message.ADT_A03;
import ca.uhn.hl7v2.model.v251.segment.AL1;
import ca.uhn.hl7v2.model.v251.segment.DG1;
import ca.uhn.hl7v2.model.v251.segment.EVN;
import ca.uhn.hl7v2.model.v251.segment.IN1;
import ca.uhn.hl7v2.model.v251.segment.MSH;
import ca.uhn.hl7v2.model.v251.segment.OBR;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import ca.uhn.hl7v2.model.v251.segment.PD1;
import ca.uhn.hl7v2.model.v251.segment.PID;
import ca.uhn.hl7v2.model.v251.segment.PRB;
import ca.uhn.hl7v2.model.v251.segment.PV1;
import ca.uhn.hl7v2.model.v251.segment.RXE;
import ca.uhn.hl7v2.model.v251.segment.SFT;

import java.io.IOException;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.mitre.synthea.world.agents.Clinician;
import org.mitre.synthea.world.agents.Payer;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord.Report;
import org.mitre.synthea.world.concepts.RaceAndEthnicity;

public class HL7V2Exporter {

    private static final String HL7_VERSION = "2.5.1";
    private static final String CW_NAMESPACE_ID = "CWNS";
    private static final String MSG_TYPE = "ADT";
    private static final String MSG_EVENT_TYPE = "A01";

    private ADT_A03 adt;
    private List<String> customSegs = new ArrayList();

    private static AtomicInteger msgCounter = new AtomicInteger(1);
    
    public static HL7V2Exporter getInstance() {
        return new HL7V2Exporter();
    }

    public String export(Person person, long time) {
        Encounter superEncounter = person.record.new Encounter(time, "super");
        for (Encounter encounter : person.record.encounters) {
            if (encounter.start <= time) {
                superEncounter.observations.addAll(encounter.observations);
                superEncounter.reports.addAll(encounter.reports);
                superEncounter.conditions.addAll(encounter.conditions);
                superEncounter.allergies.addAll(encounter.allergies);
                superEncounter.procedures.addAll(encounter.procedures);
                superEncounter.immunizations.addAll(encounter.immunizations);
                superEncounter.medications.addAll(encounter.medications);
                superEncounter.careplans.addAll(encounter.careplans);
                superEncounter.imagingStudies.addAll(encounter.imagingStudies);
            } else {
                break;
            }
        }

        person.attributes.put("UUID", UUID.randomUUID().toString());
        person.attributes.put("ehr_encounters", person.record.encounters);
        person.attributes.put("ehr_observations", superEncounter.observations);
        person.attributes.put("ehr_reports", superEncounter.reports);
        person.attributes.put("ehr_conditions", superEncounter.conditions);
        person.attributes.put("ehr_allergies", superEncounter.allergies);
        person.attributes.put("ehr_procedures", superEncounter.procedures);
        person.attributes.put("ehr_immunizations", superEncounter.immunizations);
        person.attributes.put("ehr_medications", superEncounter.medications);
        person.attributes.put("ehr_careplans", superEncounter.careplans);
        person.attributes.put("ehr_imaging_studies", superEncounter.imagingStudies);
        person.attributes.put("time", time);
        person.attributes.put("race_lookup", RaceAndEthnicity.LOOK_UP_CDC_RACE);
        person.attributes.put("ethnicity_lookup", RaceAndEthnicity.LOOK_UP_CDC_ETHNICITY_CODE);
        person.attributes.put("ethnicity_display_lookup", RaceAndEthnicity.LOOK_UP_CDC_ETHNICITY_DISPLAY);

        final StringBuilder msgContent = new StringBuilder();
        try {
            String curDT = getCurrentTimeStamp();
            adt = new ADT_A03();
            adt.initQuickstart(MSG_TYPE, MSG_EVENT_TYPE, "P");

            generateMSH(curDT);
            generateSFT();
            generateEVN(curDT);
            processPatient(person);
            processEncounters(person);
            processAllergies(person);
            processDiagnosis(person);
            procesProblems(person);
            processMedications(person);
            processProcedures(person);
            processVitals(person);
            processReports(person);

            String rawMsg = adt.encode();

            //Fix the end-of-segment default HAPI uses
            rawMsg = rawMsg.replace("\r", "\n");

            msgContent.append(rawMsg);

            //Add any non-standard segments that HAPI doesn't like
            customSegs.forEach((seg) -> {
                msgContent.append(seg);
                msgContent.append("\n");
            });
        } catch (HL7Exception | IOException ex) {
            ex.printStackTrace();
            msgContent.append(String.format("BLAMMO! error=%s", ex.getMessage()));
        }

        StringWriter writer = new StringWriter();
        writer.write(msgContent.toString());
        return writer.toString();
    }

    private static String getCurrentTimeStamp() {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    private static synchronized String generateControlId() {
        String controlId = StringUtils.leftPad(String.valueOf(msgCounter.incrementAndGet()),7,'0');
        return controlId;
    }

    private void generateMSH(String curDT) throws DataTypeException {
        MSH msh = adt.getMSH();
        msh.getFieldSeparator().setValue("|");
        msh.getEncodingCharacters().setValue("^~\\&");
        msh.getSendingApplication().getNamespaceID().setValue(CW_NAMESPACE_ID);
        msh.getSendingApplication().getUniversalID().setValue("LUCY");
        msh.getSendingFacility().getNamespaceID().setValue(CW_NAMESPACE_ID);
        msh.getSendingFacility().getUniversalID().setValue("SYNTHEA");
        msh.getReceivingApplication().getNamespaceID().setValue(CW_NAMESPACE_ID);
        msh.getReceivingApplication().getUniversalID().setValue("XXX");
        msh.getReceivingFacility().getNamespaceID().setValue(CW_NAMESPACE_ID);
        msh.getReceivingFacility().getUniversalID().setValue("YYY");
        msh.getDateTimeOfMessage().getTime().setValue(curDT);
        msh.getMessageControlID().setValue(generateControlId());
        msh.getVersionID().getVersionID().setValue(HL7_VERSION);
    }

    private void generateSFT() throws DataTypeException {
        SFT sft = adt.getSFT();
        XON xon = sft.getSft1_SoftwareVendorOrganization();
        xon.getXon1_OrganizationName().setValue("QueuePoint Systems, Inc.");
        xon.getXon2_OrganizationNameTypeCode().setValue("Q");
        sft.getSft2_SoftwareCertifiedVersionOrReleaseNumber().setValue("1.0");
        sft.getSft3_SoftwareProductName().setValue("Caterwaul");
    }

    private void generateEVN(String curDT) throws DataTypeException {
        EVN evn = adt.getEVN();
        evn.getRecordedDateTime().getTime().setValue(curDT);
    }

    private void processPatient(Person person) throws DataTypeException, HL7Exception {
        PID pid = adt.getPID();
        pid.getPid1_SetIDPID().setValue("1");
        Map<String, Object> pattrs = person.attributes;

        XPN patientName = pid.insertPatientName(0);
        patientName.getFamilyName().getSurname().setValue(getStrAttr(pattrs, "last_name"));
        patientName.getGivenName().setValue(getStrAttr(pattrs, "first_name"));
        patientName.getPrefixEgDR().setValue(getStrAttr(pattrs, "name_prefix"));

        pid.getPid2_PatientID().getCx1_IDNumber().setValue(String.valueOf(Math.abs(getStrAttr(pattrs, "UUID").hashCode())));
        CX patientId = pid.insertPatientIdentifierList(0);
        patientId.getIDNumber().setValue(getStrAttr(pattrs, "id"));
        patientId.getAssigningAuthority().getHd1_NamespaceID().setValue(CW_NAMESPACE_ID);

        XAD patientAddress = pid.insertPatientAddress(0);
        patientAddress.getStreetAddress().getStreetOrMailingAddress().setValue("123 Main Street");
        patientAddress.getCity().setValue(getStrAttr(pattrs, "city"));
        patientAddress.getStateOrProvince().setValue(getStrAttr(pattrs, "state"));
        patientAddress.getZipOrPostalCode().setValue(getStrAttr(pattrs, "zip"));
        patientAddress.getCountry().setValue(getStrAttr(pattrs, "country"));
        patientAddress.getCountyParishCode().setValue(getStrAttr(pattrs, "county"));

        //pid.getDriverSLicenseNumberPatient().setValue(pattrs.get("identifier_drivers").toString()); Not valid anymore in HL7
        //pid.getSSNNumberPatient().setValue(pattrs.get("ssn").toString()); Not valid anymore
        String raceName = getStrAttr(pattrs, "race");
        CE race = pid.insertPid10_Race(0);
        race.getText().setValue(raceName);
        race.getIdentifier().setValue(((Map) pattrs.get("race_lookup")).get(raceName).toString());

        CE ethnicGroup = pid.insertPid22_EthnicGroup(0);
        ethnicGroup.getText().setValue(((Map) pattrs.get("ethnicity_display_lookup")).get(raceName).toString());
        ethnicGroup.getIdentifier().setValue(((Map) pattrs.get("ethnicity_lookup")).get(raceName).toString());

        pid.getPid8_AdministrativeSex().setValue(getStrAttr(pattrs, "gender"));
        pid.getPid7_DateTimeOfBirth().getTime().setValue(getDateAttr(pattrs, "birthdate"));
        pid.getPid23_BirthPlace().setValue(getStrAttr(pattrs, "birthplace"));
        pid.getPid16_MaritalStatus().getIdentifier().setValue(getStrAttr(pattrs, "marital_status"));

        String mothersName = getStrAttr(pattrs, "name_mother");
        if (StringUtils.isNotBlank(mothersName)) {
            String[] nameParts = mothersName.split("\\ ");
            if (nameParts.length > 1) {

                XPN mothersMaiden = pid.insertMotherSMaidenName(0);

                mothersMaiden.getGivenName().setValue(nameParts[0]);
                mothersMaiden.getFamilyName().getSurname().setValue(nameParts[1]);
            }
        }
        XTN homePhone = pid.insertPhoneNumberHome(0);
        homePhone.getTelephoneNumber().setValue(getStrAttr(pattrs, "telecom"));

        pid.getPid15_PrimaryLanguage().getText().setValue(getStrAttr(pattrs, "first_language"));

        Provider pcp = (Provider) person.attributes.get("preferredProvideroutpatient");
        if (pcp != null) {
            PD1 pd1 = adt.getPD1();
            XCN provId = pd1.insertPatientPrimaryCareProviderNameIDNo(0);
            provId.getXcn1_IDNumber().setValue(pcp.id);
            XON pcpFac = pd1.insertPatientPrimaryFacility(0);
            pcpFac.getOrganizationName().setValue(pcp.name);
            pcpFac.getOrganizationIdentifier().setValue(pcp.id);
        }
    }

    private void processEncounters(Person person) throws DataTypeException, HL7Exception {
        List<HealthRecord.Encounter> encounters = (List<HealthRecord.Encounter>) person.record.encounters;
        Encounter e = encounters.get(0);
        PV1 pv1 = adt.getPV1();
        pv1.getPv11_SetIDPV1().setValue("1");
        IS eClass = pv1.getPv12_PatientClass();
        IS aType = pv1.getPv14_AdmissionType();
        switch (e.type) {
            case "inpatient":
                eClass.setValue("I");
                break;
            case "wellness":
                eClass.setValue("O");
                aType.setValue(e.type.toUpperCase());
                break;
            case "ambulatory":
                eClass.setValue("O");
                break;
            case "outpatient":
                eClass.setValue("O");
                break;
            case "urgentcare":
                eClass.setValue("O");
                break;
            case "emergency":
                eClass.setValue("E");
                aType.setValue("E");
                break;
            default:
                eClass.setValue(e.type.toUpperCase());
                break;
        }
        Clinician prov = e.clinician;
        if (e.provider != null) {
            pv1.getAssignedPatientLocation().getFacility().getHd1_NamespaceID().setValue(e.provider.name);
            pv1.getVisitNumber().getCx1_IDNumber().setValue(String.valueOf(e.hashCode()));
        }
        pv1.getAssignedPatientLocation().getFacility();
        pv1.getAttendingDoctor(0).getXcn1_IDNumber().setValue(String.valueOf(prov.identifier));
        pv1.getAttendingDoctor(0).getXcn2_FamilyName().getFn1_Surname().setValue((String) prov.getAttributes().get("last_name"));
        pv1.getAttendingDoctor(0).getXcn3_GivenName().setValue((String) prov.getAttributes().get("first_name"));
        if (e.start > 0) {
            pv1.getAdmitDateTime().getTime().setValue(new Date(e.start));
            if (e.stop > 0) {
                TS t = pv1.insertDischargeDateTime(0);
                t.getTime().setValue(new Date(e.stop));
            }
        }

        Payer payer = person.getPayerAtAge(person.ageInYears(e.start));
        if (payer != null) {
            ADT_A03_INSURANCE adtInsurance = adt.getINSURANCE();

            IN1 in1 = adtInsurance.getIN1();

            in1.getIn11_SetIDIN1().setValue("1");
            in1.getIn12_InsurancePlanID().getCe1_Identifier().setValue(String.valueOf(payer.uuid.hashCode()));
            XON insComp = in1.insertInsuranceCompanyName(0);
            insComp.getOrganizationName().setValue(payer.getName());
            XAD insAddr = in1.insertIn15_InsuranceCompanyAddress(0);
            insAddr.getXad1_StreetAddress().getStreetOrMailingAddress().setValue((String) payer.getAttributes().get("address"));
            insAddr.getXad3_City().setValue((String) payer.getAttributes().get("city"));
            insAddr.getXad4_StateOrProvince().setValue((String) payer.getAttributes().get("state_headquartered"));
            insAddr.getXad5_ZipOrPostalCode().setValue((String) payer.getAttributes().get("zip"));

            in1.getIn118_InsuredSDateOfBirth().getTs1_Time().setValue(getDateAttr(person.attributes, "birthdate"));
            in1.getIn117_InsuredSRelationshipToPatient().getCe1_Identifier().setValue("SEL");
            in1.getIn117_InsuredSRelationshipToPatient().getCe2_Text().setValue("SELF");

            in1.getIn137_PolicyDeductible().getCp1_Price().getMo1_Quantity().setValue(String.valueOf(payer.getDeductible()));
            in1.getIn115_PlanType().setValue(payer.getOwnership().toUpperCase());
        }
    }

    private void processAllergies(Person person) throws DataTypeException, HL7Exception {
        List<Entry> allergies = (List<Entry>) person.attributes.get("ehr_allergies");
        if (allergies == null || allergies.isEmpty()) {
            return;
        }
        Integer ac = 0;
        List<String> seenAllergenCodes = new ArrayList();
        for (Entry entry : allergies) {
            AL1 a = new AL1(adt, adt.getModelClassFactory());
            a.getAl11_SetIDAL1().setValue(String.valueOf(ac + 1));
            if (entry.codes != null && entry.codes.size() > 0) {
                Code c = entry.codes.get(0);
                if (seenAllergenCodes.contains(c.code)) {
                    //Skip it
                } else {
                    seenAllergenCodes.add(c.code);
                    a.getAl12_AllergenTypeCode().getCe1_Identifier().setValue(c.code);
                    a.getAl12_AllergenTypeCode().getCe2_Text().setValue(c.display);
                    a.getAl12_AllergenTypeCode().getCe3_NameOfCodingSystem().setValue(translateSystem(c.system));
                    adt.insertAL1(a, ac++);
                }
            }
        }
    }

    private void procesProblems(Person person) throws DataTypeException, HL7Exception {
        List<Entry> conditions = (List<Entry>) person.attributes.get("ehr_conditions");
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        Integer pc = 0;
        List<String> seenConditionCode = new ArrayList();
        for (Entry entry : conditions) {
            PRB p = new PRB(adt, adt.getModelClassFactory());
            if (entry.codes != null && entry.codes.size() > 0 && StringUtils.containsIgnoreCase(entry.codes.get(0).display, "finding")) {
                Code c = entry.codes.get(0);
                if (seenConditionCode.contains(c.code)) {
                    //Skip it
                } else {
                    seenConditionCode.add(c.code);
                    p.getProblemID().getCe1_Identifier().setValue(c.code);
                    p.getProblemID().getCe2_Text().setValue(c.display);
                    p.getProblemID().getCe3_NameOfCodingSystem().setValue(translateSystem(c.system));
                    seenConditionCode.add(c.code);

                    if (entry.start > 0) {
                        p.getProblemDateOfOnset().getTs1_Time().setValue(new Date(entry.start));
                    }
                    customSegs.add(p.encode());
                }
            }
        }
    }

    private void processDiagnosis(Person person) throws DataTypeException, HL7Exception {
        List<Entry> conditions = (List<Entry>) person.attributes.get("ehr_conditions");
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        Integer dc = 0;
        List<String> seenConditionCode = new ArrayList();
        for (Entry entry : conditions) {
            DG1 d = new DG1(adt, adt.getModelClassFactory());
            if (entry.codes != null && entry.codes.size() > 0 && !StringUtils.containsIgnoreCase(entry.codes.get(0).display, "finding")) {
                d.getDg11_SetIDDG1().setValue(String.valueOf(dc + 1));
                Code c = entry.codes.get(0);
                if (seenConditionCode.contains(c.code)) {
                    //Skip it
                } else {
                    seenConditionCode.add(c.code);
                    d.getDiagnosisCodeDG1().getCe1_Identifier().setValue(c.code);
                    d.getDiagnosisCodeDG1().getCe2_Text().setValue(c.display);
                    d.getDiagnosisCodeDG1().getCe3_NameOfCodingSystem().setValue(translateSystem(c.system));
                    seenConditionCode.add(c.code);

                    if (entry.start > 0) {
                        d.getDiagnosisDateTime().getTs1_Time().setValue(new Date(entry.start));
                    }
                    adt.insertDG1(d, dc++);
                }
            }
        }
    }

    private void processMedications(Person person) throws DataTypeException, HL7Exception {
        Map<String, HealthRecord.Medication> meds = person.chronicMedications;
        if (meds == null || meds.isEmpty()) {
            return;
        }
        Integer mc = 0;
        List<String> seenMedCodes = new ArrayList();
        for (HealthRecord.Medication med : meds.values()) {
            RXE m = new RXE(adt, adt.getModelClassFactory());
            if (med.codes != null && med.codes.size() > 0) {
                Code medCode = med.codes.get(0);
                if (seenMedCodes.contains(medCode.code)) {
                    //Skip it
                } else {
                    seenMedCodes.add(medCode.code);
                    m.getGiveCode().getCe1_Identifier().setValue(medCode.code);
                    m.getGiveCode().getCe2_Text().setValue(medCode.display);
                    m.getGiveCode().getCe3_NameOfCodingSystem().setValue(medCode.system.toUpperCase());
                    if (med.reasons != null && med.reasons.size() > 0) {
                        Integer rc = 0;
                        for (Code medReasonCode : med.reasons) {
                            CE g = m.insertGiveIndication(rc);
                            g.getCe1_Identifier().setValue(medReasonCode.code);
                            g.getCe2_Text().setValue(medReasonCode.display);
                            g.getCe3_NameOfCodingSystem().setValue(medReasonCode.system);
                            rc++;
                        }
                    }
                    if (med.prescriptionDetails != null) {
                        if (med.prescriptionDetails.get("dosage") != null) {
                            String dosageUnits = med.prescriptionDetails.get("dosage").getAsJsonObject().get("unit").getAsString();
                            Integer dosageFreq = med.prescriptionDetails.get("dosage").getAsJsonObject().get("frequency").getAsInt();
                            Integer dosageAmt = med.prescriptionDetails.get("dosage").getAsJsonObject().get("amount").getAsInt();
                            m.getGiveRateAmount().setValue(dosageAmt.toString() + "/" + dosageUnits);
                            m.getGivePerTimeUnit().setValue(dosageFreq.toString());
                            if (med.prescriptionDetails.get("duration") != null) {
                                String durationUnits = med.prescriptionDetails.get("duration").getAsJsonObject().get("unit").getAsString();
                                Integer durationQty = med.prescriptionDetails.get("duration").getAsJsonObject().get("quantity").getAsInt();
                                m.getGiveAmountMaximum().setValue(String.valueOf(durationQty * dosageAmt));
                            }
                        }

                    }

                    customSegs.add(m.encode());
                }
            }
        }
    }

    private void processProcedures(Person person) throws DataTypeException, HL7Exception {
        List<Entry> procs = (List<Entry>) person.attributes.get("ehr_procedures");
        if (procs == null || procs.isEmpty()) {
            return;
        }
        List<String> seenProcCodes = new ArrayList();
        Integer pc = 0;
        for (Entry gEntry : procs) {
            HealthRecord.Procedure entry = (HealthRecord.Procedure) gEntry;
            ADT_A03_PROCEDURE p = new ADT_A03_PROCEDURE(adt, adt.getModelClassFactory());
            if (entry.codes != null && entry.codes.size() > 0) {
                p.getPR1().getPr11_SetIDPR1().setValue(String.valueOf(pc + 1));
                Code procCode = entry.codes.get(0);
                if (seenProcCodes.contains(procCode.code)) {
                    //Skip it
                } else {
                    seenProcCodes.add(procCode.code);
                    p.getPR1().getProcedureCode().getCe1_Identifier().setValue(procCode.code);
                    p.getPR1().getProcedureCode().getCe2_Text().setValue(procCode.display);
                    p.getPR1().getProcedureCode().getCe3_NameOfCodingSystem().setValue(procCode.system);

                    if (entry.reasons != null && entry.reasons.size() > 0) {
                        Code procAssocDiagCode = entry.reasons.get(0);
                        p.getPR1().getAssociatedDiagnosisCode().getCe1_Identifier().setValue(procAssocDiagCode.code);
                        p.getPR1().getAssociatedDiagnosisCode().getCe2_Text().setValue(procAssocDiagCode.display);
                        p.getPR1().getAssociatedDiagnosisCode().getCe2_Text().setValue(procAssocDiagCode.system);
                    }
                    if (entry.start > 0) {
                        p.getPR1().getPr15_ProcedureDateTime().getTime().setValue(new Date(entry.start));
                    }
                    adt.insertPROCEDURE(p, pc++);
                }
            }
        }
    }

    private void processVitals(Person person) throws HL7Exception {
        List<HealthRecord.Encounter> encounters = (List<HealthRecord.Encounter>) person.attributes.get("ehr_encounters");
        AtomicInteger ox = new AtomicInteger(1);
        List<String> seenVitalCodes = new ArrayList();

        OBR obr = createObservationGroup("", "", "Vitals", "8716-3", "LN");
        customSegs.add(obr.encode());
        for (Encounter encounter : encounters) {
            if (encounter.observations.size() > 0) {
                for (Observation obs : encounter.observations) {
                    if (obs.observations != null && obs.observations.size() > 0) {
                        for (Observation subObs : obs.observations) {
                            if (subObs.category != null && subObs.category.startsWith("vital")) {
                                addObservation(subObs, ox, seenVitalCodes);
                            }
                        }
                    } else {
                        if (obs.category != null && obs.category.startsWith("vital")) {
                            addObservation(obs, ox, seenVitalCodes);
                        }
                    }
                }
            }
        }
    }

    private void processReports(Person person) throws DataTypeException, HL7Exception {
        List<Report> reports = (List<Report>) person.attributes.get("ehr_reports");
        if (reports == null || reports.isEmpty()) {
            return;
        }
        int maxRpts = 3;
        for (Report r : reports) {
            Code rc = r.codes.get(0);
            OBR obr = createObservationGroup(null, null, rc);
            customSegs.add(obr.encode());
            List<String> seenCodes = new ArrayList();
            AtomicInteger ox = new AtomicInteger(1);
            for (Observation o : r.observations) {
                addObservation(o, ox, seenCodes);
            }
            maxRpts--;
            if (maxRpts==0) {
                break;
            }
        }
    }

    private OBR createObservationGroup(String placer, String filler, String name, String code, String codeSystem) throws DataTypeException, HL7Exception {
        return createObservationGroup(placer, filler, new Code(codeSystem, code, name));
    }

    private OBR createObservationGroup(String placer, String filler, Code code) throws DataTypeException, HL7Exception {
        OBR v = new OBR(adt, adt.getModelClassFactory());
        v.getObr1_SetIDOBR().setValue("1");
        if (placer==null) {
            placer = RandomStringUtils.random(5, false, true);
        }
        if (filler==null) {
            filler = RandomStringUtils.random(5, false, true);
        }        
        v.getObr2_PlacerOrderNumber().getEi1_EntityIdentifier().setValue(placer);
        v.getObr3_FillerOrderNumber().getEi1_EntityIdentifier().setValue(filler);;
        v.getObr4_UniversalServiceIdentifier().getCe1_Identifier().setValue(code.code);
        v.getObr4_UniversalServiceIdentifier().getCe2_Text().setValue(code.display);
        v.getObr4_UniversalServiceIdentifier().getCe3_NameOfCodingSystem().setValue(code.system);
        return v;
    }

    private void addObservation(Observation obs, AtomicInteger setIdCounter, List<String> seenVitalCodes) throws DataTypeException, HL7Exception {
        if (obs.codes != null && obs.codes.size() > 0) {
            OBX v = new OBX(adt, adt.getModelClassFactory());

            v.getObx1_SetIDOBX().setValue(setIdCounter.toString());

            Code obscode = obs.codes.get(0);
            //Checking to see if we've already added one of these.  If so, let's keep it simple and stop. 
            if (seenVitalCodes.contains(obscode.code)) {
                return;
            }
            //Otherwise, track that we're adding one
            seenVitalCodes.add(obscode.code);

            v.getObx3_ObservationIdentifier().getCe1_Identifier().setValue(obscode.code);
            v.getObx3_ObservationIdentifier().getCe2_Text().setValue(obscode.display);
            v.getObx3_ObservationIdentifier().getCe3_NameOfCodingSystem().setValue(obscode.system);

            //Set the Units
            v.getObx6_Units().getCe2_Text().setValue(obs.unit);

            //Generate a slot for the value
            v.insertObx5_ObservationValue(0);
            //Based on the object class of obs.value, generate the appropriate HL7 DataType and populate it into OBX5
            //We set OBX2 to the DataType (e.g., ST or NM)
            if (obs.value instanceof Double) {
                NM nm = new NM(adt);
                //Fix Values that are some crazy number of digits of precision or scientific notation e.g. 3.777961337608282E-4
                obs.value = new BigDecimal((Double) obs.value).round(new MathContext(8));

                nm.parse(obs.value.toString());
                v.getObx5_ObservationValue()[0].setData(nm);
                v.getObx2_ValueType().setValue("NM");
            } else if (obs.value instanceof HealthRecord.Code) {
                Code c = (HealthRecord.Code) obs.value;
                CWE cwe = new CWE(adt);
                cwe.getCwe1_Identifier().setValue(c.code);
                cwe.getCwe2_Text().setValue(c.display);
                cwe.getCwe3_NameOfCodingSystem().setValue(translateSystem(c.system));
                v.getObx5_ObservationValue()[0].setData(cwe);
                v.getObx2_ValueType().setValue("CWE");
            } else if (obs.value instanceof String) {
                ST st = new ST(adt);
                st.setValue(obs.value.toString());
                v.getObx5_ObservationValue()[0].setData(st);
                v.getObx2_ValueType().setValue("ST");
            } else {
                throw new HL7Exception("Unrecognized datatype" + obs.value.getClass().getName());
            }
            //Set the time for the observation
            v.getDateTimeOfTheObservation().getTime().setValue(new Date(obs.start));
            //Add it to the  list of observations
            //To preserve order of the OBX's, use 'custom'
            customSegs.add(v.encode());
            setIdCounter.incrementAndGet();
        }
    }

    private String translateSystem(String systemName) {
        switch (systemName) {
            case "snomed-ct":
                return "SCT";
            case "loinc":
                return "LN";
            case "rxnorm":
                return "RXNORM";
            default:
                if (StringUtils.isBlank(systemName)) {
                    return "????";
                }
                return systemName.toUpperCase();
        }
    }

    private String getStrAttr(Map<String, Object> pattrs, String key) {
        if (pattrs.containsKey(key)) {
            return (String) pattrs.get(key);
        }
        return null;
    }

    private Date getDateAttr(Map<String, Object> pattrs, String key) throws DataTypeException {
        try {
            Long dateL = (Long) pattrs.get(key);
            if (dateL != null) {
                Date d = new Date(dateL);
                return d;
            }
        } catch (RuntimeException e) {
            throw new DataTypeException("Couldn't parse attribute from key=" + key);
        }
        return null;
    }

}
