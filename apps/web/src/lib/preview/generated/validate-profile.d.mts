declare const validateProfile: ((data: unknown) => boolean) & {
  errors?: Array<{ instancePath: string; message?: string }> | null;
};
export default validateProfile;
