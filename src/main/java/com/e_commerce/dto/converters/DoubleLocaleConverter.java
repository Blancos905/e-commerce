package com.e_commerce.dto.converters;

import com.opencsv.bean.AbstractBeanField;
import com.opencsv.exceptions.CsvDataTypeMismatchException;

/**
 * Converte stringhe numeriche con virgola decimale (formato italiano/es: "5,25", "14,99")
 * in Double per il campo prezzo nel CSV import.
 */
public class DoubleLocaleConverter extends AbstractBeanField<Object, Integer> {

    @Override
    protected Object convert(String value) throws CsvDataTypeMismatchException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String s = value.trim().replace(',', '.');
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            throw new CsvDataTypeMismatchException(value, Double.class, "Valore non numerico: " + value);
        }
    }
}
