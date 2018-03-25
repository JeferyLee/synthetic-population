package bnw.abm.intg.synthesis;

import bnw.abm.intg.synthesis.models.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FamilyFactory {

    private final Random random;
    private final ExtrasHandler extrasHandler;
    private AgeRange.AgeComparator ageComparator = new AgeRange.AgeComparator();

    FamilyFactory(Random random, ExtrasHandler extrasHandler) {
        this.random = random;
        this.extrasHandler = extrasHandler;
    }


    /**
     * Pairs all the lone parents with a suitable child. This alters input lists.
     *
     * @param count       The number of basic One Parent families to form
     * @param loneParents The list of lone parents in the population
     * @param children    The list of children
     * @return A list of basic one parent family units with one lone parent and a child
     */
    List<Family> formOneParentBasicUnits(int count, List<Person> loneParents, List<Person> children) {
        if (count > loneParents.size()) {//We don't have enough Lone Parents. So using extras.

            //Form lone parents older than the oldest child. Otherwise we may not be able to find children for newly formed parents
            children.sort(ageComparator.reversed());
            List<AgeRange> loneParentAges = Stream.of(AgeRange.values())
                                                  .filter(pa -> PopulationRules.validateParentChildAgeRule(pa,
                                                                                                           children.get(0).getAgeRange()))
                                                  .collect(Collectors.toList());
            int newLoneParentsCount = count - loneParents.size();
            loneParents.addAll(extrasHandler.getPersonsFromExtras(Collections.singletonList(RelationshipStatus.LONE_PARENT),
                                                                  null, //Sex automatically decided by data distribution
                                                                  loneParentAges,
                                                                  newLoneParentsCount));
        }


        if (count > children.size()) {
            loneParents.sort(ageComparator);
            List<AgeRange> childAges = Stream.of(AgeRange.values())
                                             .filter(ca -> PopulationRules.validateParentChildAgeRule(loneParents.get(0).getAgeRange(), ca))
                                             .collect(Collectors.toList());
            int childrenToForm = count - children.size();
            children.addAll(extrasHandler.getChildrenFromExtras(null, childAges, childrenToForm));
        }


        Collections.shuffle(loneParents, random); //Mixes male and females to remove any bias to parent's gender
        loneParents.sort(ageComparator.reversed());//Sort by age. Males and females are still mixed
        Collections.shuffle(children, random); //Mixes children to remove any bias to a gender
        children.sort(ageComparator.reversed());

        List<Family> lnParentBasic = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (children.isEmpty()) {
                throw new NotEnoughPersonsException("One Parent Basic: Not enough children - units successfully formed: " + lnParentBasic.size());
            }

            Family f = new Family(FamilyType.ONE_PARENT);
            Person loneParent = loneParents.remove(0);
            f.addMember(loneParent);
            boolean success = addChildToFamily(f, children);
            if (success) {
                lnParentBasic.add(f);
            } else {
                loneParents.add(loneParent);
            }
        }
        return lnParentBasic;
    }

    /**
     * Forms basic other family units need for households with an Other Family as the primary family. A family is created by randomly
     * selecting two relatives.
     *
     * @param count     The needed number of Other Family units
     * @param relatives The list of relatives in the population
     * @return The list of basic Other Family units
     */
    List<Family> formOtherFamilyBasicUnits(int count, List<Person> relatives) {

        if (count * 2 > relatives.size()) {

            int newRelativesCount = (count * 2) - relatives.size();
            relatives.addAll(extrasHandler.getPersonsFromExtras(RelationshipStatus.RELATIVE,
                                                                null,
                                                                null,
                                                                newRelativesCount));
        }

        List<Family> otherFamilyBasic = new ArrayList<>();
        Collections.shuffle(relatives, random);

        for (int i = 0; i < count; i++) {
            if (relatives.size() < 2) {
                throw new NotEnoughPersonsException(
                        "Basic Other Family: Not enough Relatives - successfully formed units: " + otherFamilyBasic.size());
            }
            Family f = new Family(FamilyType.OTHER_FAMILY);
            f.addMember(relatives.remove(0));
            f.addMember(relatives.remove(0));
            otherFamilyBasic.add(f);
        }

        return otherFamilyBasic;
    }

    /**
     * Forms basic married couple units. Only consider heterosexual relationships. First sorts all males and females in age descending
     * order. Then pair them in order they appear in respective lists. This ensures age wise natural looking relationships. Method alters
     * input lists. If there are not enough married males or females, new instances are created from extras.
     *
     * @param count          The number of couples to make
     * @param marriedMales   list of married males
     * @param marriedFemales list of married females
     * @return list of couples
     */
    List<Family> formCoupleFamilyBasicUnits(int count, List<Person> marriedMales, List<Person> marriedFemales) {

        if (count > marriedMales.size()) {

            int newMalesCount = count - marriedMales.size();
            marriedMales.addAll(extrasHandler.getPersonsFromExtras(RelationshipStatus.MARRIED,
                                                                   Sex.Male,
                                                                   null,
                                                                   newMalesCount));
        }

        if (count > marriedFemales.size()) {

            int newFemalesCount = count - marriedFemales.size();
            marriedFemales.addAll(extrasHandler.getPersonsFromExtras(RelationshipStatus.MARRIED,
                                                                     Sex.Female,
                                                                     null,
                                                                     newFemalesCount));
        }

        //Sort two lists in age descending order
        //TODO: Younger married persons may be over represented in married-extra list
        marriedMales.sort(ageComparator.reversed());
        marriedFemales.sort(ageComparator.reversed());

        int diff = marriedMales.size() - marriedFemales.size();

        List<Family> couples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Family f = new Family(FamilyType.COUPLE_ONLY);
            f.addMember(marriedMales.remove(0));
            f.addMember(marriedFemales.remove(0));
            couples.add(f);
        }
        return couples;
    }

    /**
     * Forms basic couple with children family units needed for households where a couple with children is the primary family. This method
     * alters couples and children list
     *
     * @param count    The needed number of couple with children basic family units
     * @param couples  The couple units in the population
     * @param children The children in the population
     * @return Basic couple with children family units for primary families.
     */
    List<Family> formCoupleWithChildFamilyBasicUnits(int count,
                                                     List<Family> couples,
                                                     List<Person> children) {

        if (count > couples.size()) {
            throw new NotEnoughPersonsException("Basic Couple With Children: required units: " + count + " available couples: " + couples.size());
        }

        if (count > children.size()) {
            couples.sort(new AgeRange.YoungestParentAgeComparator());
            List<AgeRange> childAges = Stream.of(AgeRange.values())
                                             .filter(ca -> PopulationRules.validateParentChildAgeRule(couples.get(0)
                                                                                                             .getYoungestParent()
                                                                                                             .getAgeRange(), ca))
                                             .collect(Collectors.toList());
            int childrenToForm = count - children.size();
            children.addAll(extrasHandler.getChildrenFromExtras(null, childAges, childrenToForm));
        }

        couples.sort(new AgeRange.YoungestParentAgeComparator().reversed());
        children.sort(ageComparator.reversed());

        List<Family> cplWithChildUnits = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            if (couples.isEmpty()) {
                throw new NotEnoughPersonsException(
                        "Basic Couple With Children: not enough Couples - units successfully formed: " + cplWithChildUnits.size());
            }

            Family f = couples.remove(0);
            boolean success = addChildToFamily(f, children);
            if (success) {
                f.setType(FamilyType.COUPLE_WITH_CHILDREN);
                cplWithChildUnits.add(f);
            } else {
                couples.add(f); // move to end of the list to filter out failed couples
            }

        }

        return cplWithChildUnits;
    }


    /**
     * Adds a new child to the family considering population rules. Returns true if a suitable child is found and added to the family.
     * Returns false of a suitable child was not found, the family is not changed.
     *
     * @param family   The family to add a child
     * @param children The list of children to select a child from
     * @return True if a suitable child was found and added to the child, else false.
     */
    private boolean addChildToFamily(Family family, List<Person> children) {
        Person youngestParent = family.getYoungestParent();

        for (int i = 0; i < children.size(); i++) {
            if (PopulationRules.validateParentChildAgeRule(youngestParent.getAgeRange(), children.get(i).getAgeRange())) {
                family.addMember(children.remove(i));
                return true;
            }
        }

        return false;
    }
}
