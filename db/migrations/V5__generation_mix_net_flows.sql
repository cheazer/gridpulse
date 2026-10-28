-- Event schema 1.1.0: generation mix values are net. Interconnectors are negative when GB exports,
-- and pumped storage is negative while pumping (seen in live Elexon FUELHH data). Every other fuel
-- still has to be non-negative.

ALTER TABLE generation_mix DROP CONSTRAINT generation_mix_value_check;
ALTER TABLE generation_mix ADD CONSTRAINT generation_mix_value_sign
    CHECK (value >= 0 OR fuel IN ('imports', 'storage'));
