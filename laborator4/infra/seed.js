// Populates the warehouse DB with the same initial data (idempotent: only when empty).
// Executed once by the mongo-init job against the replica-set primary; MongoDB replication
// then synchronises the data to all other members.
const wh = db.getSiblingDB("warehouse");
if (wh.employees.countDocuments() > 0) {
  print("[seed] employees already present: " + wh.employees.countDocuments() + " -> skipping");
} else {
  const people = [
    ["Ana", "Popescu", "Engineering", "Backend Developer", 4200, "2019-03-11"],
    ["Mihai", "Ionescu", "Engineering", "Frontend Developer", 3900, "2020-06-01"],
    ["Elena", "Rusu", "Engineering", "DevOps Engineer", 4600, "2018-09-17"],
    ["Andrei", "Munteanu", "Engineering", "Tech Lead", 6100, "2016-02-22"],
    ["Ioana", "Georgescu", "HR", "HR Manager", 3500, "2017-05-08"],
    ["Vlad", "Stan", "HR", "Recruiter", 2800, "2021-01-18"],
    ["Cristina", "Dumitru", "Finance", "Accountant", 3300, "2019-11-04"],
    ["Radu", "Constantin", "Finance", "Financial Analyst", 3800, "2020-02-10"],
    ["Maria", "Marin", "Finance", "CFO", 7200, "2015-07-13"],
    ["Alex", "Lungu", "Sales", "Sales Representative", 2900, "2022-03-07"],
    ["Diana", "Tudor", "Sales", "Account Manager", 3400, "2021-08-23"],
    ["Stefan", "Barbu", "Sales", "Sales Director", 6400, "2016-10-31"],
    ["Laura", "Cojocaru", "Support", "Support Agent", 2400, "2022-09-12"],
    ["Gabriel", "Neagu", "Support", "Support Lead", 3300, "2018-04-02"],
    ["Teodora", "Preda", "Engineering", "QA Engineer", 3600, "2020-12-14"],
    ["Bogdan", "Sandu", "Engineering", "Data Engineer", 4800, "2019-08-26"],
    ["Alina", "Matei", "Marketing", "Marketing Specialist", 3100, "2021-05-17"],
    ["Cosmin", "Dinu", "Marketing", "Content Manager", 3000, "2022-01-24"],
    ["Raluca", "Pavel", "Engineering", "UX Designer", 3700, "2020-10-05"],
    ["Daniel", "Lazar", "Engineering", "Security Engineer", 5200, "2017-12-11"],
  ];
  const now = new Date();
  const docs = people.map((p, i) => ({
    _id: String(i + 1),
    firstName: p[0],
    lastName: p[1],
    email: (p[0] + "." + p[1] + "@company.test").toLowerCase(),
    department: p[2],
    position: p[3],
    salary: p[4],
    hireDate: p[5],
    updatedAt: now,
    _class: "com.pad.lab3.dw.Employee",
  }));
  wh.employees.insertMany(docs, { writeConcern: { w: "majority" } });
  print("[seed] inserted " + docs.length + " employees");
}
